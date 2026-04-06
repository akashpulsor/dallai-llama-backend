package com.dalai.llama.pbx.core.service.cdr;


import com.dalai.llama.pbx.core.domain.entity.cdr.CallRecord;
import com.dalai.llama.pbx.core.domain.enums.CallDirection;
import com.dalai.llama.pbx.core.domain.enums.CallStatus;
import com.dalai.llama.pbx.core.repository.cdr.CallRecordRepository;
import com.dalai.llama.pbx.core.service.cache.TenantConfigCacheService;
import com.dalai.llama.pbx.core.service.storage.BlobStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Call Detail Record lifecycle management.
 *
 * CDR flow:
 *   1. call-start event (Kamailio) → createCdr() — status=RINGING
 *   2. CHANNEL_ANSWER event (ESL)  → markAnswered() — status=ANSWERED, answer_time set
 *   3. call-end event (Kamailio)   → markEnded() — status=COMPLETED, duration calc, billing cost
 *   4. Recording finalized (ESL)   → setRecordingUrl()
 *   5. AI transcript ready         → setTranscript()
 *
 * Billing cost calculation:
 *   billable_seconds = duration - 1s grace period (minimum 0)
 *   billable_minutes = ceil(billable_seconds / 60)
 *   cost = billable_minutes × rate_per_minute (from TenantApp config)
 *
 * Rate lookup: TenantConfigCacheService → TenantApp.ratePerMinuteInbound/Outbound
 */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CDR lifecycle + billing event + recording/transcript storage.
 *
 * CDR flow:
 *   1. call-start (Kamailio)   → createCdr() — RINGING
 *   2. CHANNEL_ANSWER (ESL)    → markAnswered() — ANSWERED
 *   3. call-end (Kamailio)     → markEnded() — COMPLETED, cost calc, Kafka billing event
 *   4. Recording (ESL)         → uploadRecording() — MinIO upload, URL saved
 *   5. Transcript (voice-brain) → uploadTranscript() — MinIO upload, URL saved
 *
 * Kafka events → billing-service:
 *   Topic: call.billing
 *   Event: {tenantId, subscriptionId, callId, direction, billableSeconds, cost, ratePerMinute, aiMinutes}
 *   billing-service debits wallet on receive.
 *
 * Storage (MinIO / Hetzner Object Storage — S3-compatible):
 *   Recordings: {tenant_id}/recordings/{yyyy}/{MM}/{callId}.wav
 *   Transcripts: {tenant_id}/transcripts/{yyyy}/{MM}/{callId}.json
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CdrService {

    private final CallRecordRepository cdrRepository;
    private final TenantConfigCacheService configCache;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final BlobStorageService blobStorage;

    private static final String BILLING_TOPIC = "call.billing";
    private static final String CDR_TOPIC = "call.cdr";

    // ═══════════════════════════════════════════════════════════
    // CREATE
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public CallRecord createCdr(UUID tenantId, UUID subscriptionId, String callId,
                                CallDirection direction, String callerNumber,
                                String calleeNumber, String didNumber, String productCode) {

        Optional<CallRecord> existing = cdrRepository.findByCallId(callId);
        if (existing.isPresent()) return existing.get();

        CallRecord cdr = CallRecord.builder()
                .tenantId(tenantId).subscriptionId(subscriptionId)
                .callId(callId).direction(direction)
                .callerNumber(callerNumber).calleeNumber(calleeNumber)
                .didNumber(didNumber).productCode(productCode)
                .status(CallStatus.RINGING).startTime(Instant.now())
                .build();

        cdr = cdrRepository.save(cdr);
        log.debug("CDR created: callId={} tenant={} dir={}", callId, tenantId, direction);
        return cdr;
    }

    // ═══════════════════════════════════════════════════════════
    // ANSWER
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void markAnswered(String callId, UUID agentId, UUID queueId) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            cdr.setStatus(CallStatus.ANSWERED);
            cdr.setAnswerTime(Instant.now());
            if (agentId != null) cdr.setAgentId(agentId);
            if (queueId != null) cdr.setQueueId(queueId);
            cdrRepository.save(cdr);
        });
    }

    // ═══════════════════════════════════════════════════════════
    // END — cost calc + Kafka billing event
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void markEnded(String callId, String hangupCause) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            Instant endTime = Instant.now();
            cdr.setEndTime(endTime);
            cdr.setHangupCause(hangupCause);

            if (cdr.getAnswerTime() != null) {
                cdr.setStatus(CallStatus.COMPLETED);
            } else {
                cdr.setStatus("NORMAL_CLEARING".equals(hangupCause) ? CallStatus.MISSED : CallStatus.FAILED);
            }

            // Duration
            if (cdr.getStartTime() != null) {
                long totalSec = endTime.getEpochSecond() - cdr.getStartTime().getEpochSecond();
                cdr.setDurationSeconds((int) Math.max(0, totalSec));
                if (cdr.getAnswerTime() != null) {
                    long talkSec = endTime.getEpochSecond() - cdr.getAnswerTime().getEpochSecond();
                    cdr.setBillableSeconds((int) Math.max(0, talkSec - 1));
                }
            }

            // Cost
            calculateCost(cdr);
            cdrRepository.save(cdr);

            // Kafka → billing-service debits wallet
            if (cdr.getStatus() == CallStatus.COMPLETED && cdr.getCost() != null
                    && cdr.getCost().compareTo(BigDecimal.ZERO) > 0) {
                sendBillingEvent(cdr);
            }

            // Kafka → CDR event for analytics/reporting
            sendCdrEvent(cdr);

            log.debug("CDR ended: callId={} status={} dur={}s cost={}",
                    callId, cdr.getStatus(), cdr.getDurationSeconds(), cdr.getCost());
        });
    }

    // ═══════════════════════════════════════════════════════════
    // RECORDING UPLOAD — FreeSWITCH local file → MinIO
    // ═══════════════════════════════════════════════════════════

    /**
     * Called by ESL event handler after RECORD_STOP.
     * FreeSWITCH saves recording locally at /recordings/{uuid}.wav.
     * We upload to MinIO and save URL in CDR.
     */
    @Transactional
    public void uploadRecording(String callId, byte[] audioData) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            String path = buildRecordingPath(cdr);
            String url = blobStorage.upload(blobStorage.getRecordingsBucket(),path, audioData, "audio/wav");
            cdr.setRecordingUrl(url);
            cdrRepository.save(cdr);
            log.debug("Recording uploaded: callId={} path={}", callId, path);
        });
    }

    /**
     * Alternative: upload from local file path on FreeSWITCH server.
     * ESL handler reads file bytes and calls this.
     */
    @Transactional
    public void setRecordingUrl(String callId, String recordingUrl) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            cdr.setRecordingUrl(recordingUrl);
            cdrRepository.save(cdr);
        });
    }

    // ═══════════════════════════════════════════════════════════
    // TRANSCRIPT UPLOAD — voice-brain JSON → MinIO
    // ═══════════════════════════════════════════════════════════

    /**
     * Called by AiController POST /internal/ai/transcript/final.
     * Stores summary in DB column + full diarized transcript in MinIO.
     *
     * @param callId          FreeSWITCH UUID
     * @param summary         Short summary for DB column (CDR list view)
     * @param sentimentScore  Overall sentiment -1.0 to 1.0
     * @param diarizedJson    Full transcript with speaker labels, timestamps, intents (can be null)
     */
    @Transactional
    public void uploadTranscript(String callId, String summary, BigDecimal sentimentScore, String diarizedJson) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            // Summary in DB column (for quick display in CDR list)
            if (summary != null) cdr.setTranscriptSummary(summary);
            if (sentimentScore != null) cdr.setSentimentScore(sentimentScore);

            // Full diarized transcript to MinIO (for detailed view / export)
            if (diarizedJson != null && !diarizedJson.isBlank()) {
                String path = buildTranscriptPath(cdr);
                String url = blobStorage.upload(blobStorage.getRecordingsBucket(),path, diarizedJson.getBytes(), "application/json");
                cdr.setTranscriptUrl(url);
                log.debug("Transcript uploaded: callId={} path={}", callId, path);
            }

            cdrRepository.save(cdr);
        });
    }

    /**
     * Backward-compatible: summary only, no blob upload.
     */
    @Transactional
    public void setTranscript(String callId, String summary, BigDecimal sentimentScore) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            if (summary != null) cdr.setTranscriptSummary(summary);
            if (sentimentScore != null) cdr.setSentimentScore(sentimentScore);
            cdrRepository.save(cdr);
        });
    }

    @Transactional
    public void setAiMinutes(String callId, BigDecimal aiMinutes) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            cdr.setAiMinutes(aiMinutes);
            cdrRepository.save(cdr);
        });
    }

    @Transactional
    public void setAgentId(String callId, UUID agentId) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            cdr.setAgentId(agentId);
            cdrRepository.save(cdr);
        });
    }

    // ═══════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════

    public Optional<CallRecord> getByCallId(String callId) {
        return cdrRepository.findByCallId(callId);
    }

    public Page<CallRecord> getByTenantId(UUID tenantId, Pageable pageable) {
        return cdrRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }

    public Page<CallRecord> getByTenantIdAndPeriod(UUID tenantId, Instant from, Instant to, Pageable pageable) {
        return cdrRepository.findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(tenantId, from, to, pageable);
    }

    public Page<CallRecord> getByAgentId(UUID agentId, Pageable pageable) {
        return cdrRepository.findByAgentIdOrderByCreatedAtDesc(agentId, pageable);
    }

    // ═══════════════════════════════════════════════════════════
    // KAFKA EVENTS
    // ═══════════════════════════════════════════════════════════

    private void sendBillingEvent(CallRecord cdr) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_type", "CALL_BILLING");
            event.put("tenant_id", cdr.getTenantId().toString());
            event.put("subscription_id", cdr.getSubscriptionId() != null ? cdr.getSubscriptionId().toString() : null);
            event.put("call_id", cdr.getCallId());
            event.put("direction", cdr.getDirection().name());
            event.put("product_code", cdr.getProductCode());
            event.put("billable_seconds", cdr.getBillableSeconds());
            event.put("rate_per_minute", cdr.getRatePerMinute());
            event.put("cost", cdr.getCost());
            event.put("ai_minutes", cdr.getAiMinutes());
            event.put("timestamp", Instant.now().toString());

            kafkaTemplate.send(BILLING_TOPIC, cdr.getTenantId().toString(), event);
            log.debug("Billing event sent: callId={} cost={}", cdr.getCallId(), cdr.getCost());
        } catch (Exception e) {
            // Billing event failure should NOT fail the CDR update
            log.error("Failed to send billing event for callId={}: {}", cdr.getCallId(), e.getMessage());
        }
    }

    private void sendCdrEvent(CallRecord cdr) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_type", "CALL_ENDED");
            event.put("tenant_id", cdr.getTenantId().toString());
            event.put("call_id", cdr.getCallId());
            event.put("direction", cdr.getDirection().name());
            event.put("status", cdr.getStatus().name());
            event.put("duration_seconds", cdr.getDurationSeconds());
            event.put("hangup_cause", cdr.getHangupCause());
            event.put("timestamp", Instant.now().toString());

            kafkaTemplate.send(CDR_TOPIC, cdr.getTenantId().toString(), event);
        } catch (Exception e) {
            log.warn("Failed to send CDR event for callId={}: {}", cdr.getCallId(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // BILLING
    // ═══════════════════════════════════════════════════════════

    private void calculateCost(CallRecord cdr) {
        if (cdr.getBillableSeconds() == null || cdr.getBillableSeconds() <= 0) {
            cdr.setCost(BigDecimal.ZERO);
            cdr.setRatePerMinute(BigDecimal.ZERO);
            return;
        }
        BigDecimal rate = lookupRate(cdr.getTenantId(), cdr.getDirection());
        cdr.setRatePerMinute(rate);
        if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal billableMin = BigDecimal.valueOf(cdr.getBillableSeconds())
                    .divide(BigDecimal.valueOf(60), 4, RoundingMode.CEILING);
            cdr.setCost(billableMin.multiply(rate).setScale(4, RoundingMode.HALF_UP));
        } else {
            cdr.setCost(BigDecimal.ZERO);
        }
    }

    private BigDecimal lookupRate(UUID tenantId, CallDirection direction) {
        try {
            Optional<Map<String, Object>> config = configCache.getConfig(tenantId);
            if (config.isPresent()) {
                String key = direction == CallDirection.INBOUND ? "ratePerMinuteInbound" : "ratePerMinuteOutbound";
                String keySnake = direction == CallDirection.INBOUND ? "rate_per_minute_inbound" : "rate_per_minute_outbound";
                Object v = config.get().get(key);
                if (v == null) v = config.get().get(keySnake);
                if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
                if (v instanceof String s && !s.isBlank()) return new BigDecimal(s);
            }
        } catch (Exception e) {
            log.warn("Rate lookup failed for tenant {}: {}", tenantId, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    // ═══════════════════════════════════════════════════════════
    // STORAGE PATHS
    // ═══════════════════════════════════════════════════════════

    private String buildRecordingPath(CallRecord cdr) {
        java.time.YearMonth ym = java.time.YearMonth.now();
        return "%s/recordings/%d/%02d/%s.wav".formatted(
                cdr.getTenantId(), ym.getYear(), ym.getMonthValue(), cdr.getCallId());
    }

    private String buildTranscriptPath(CallRecord cdr) {
        java.time.YearMonth ym = java.time.YearMonth.now();
        return "%s/transcripts/%d/%02d/%s.json".formatted(
                cdr.getTenantId(), ym.getYear(), ym.getMonthValue(), cdr.getCallId());
    }
}