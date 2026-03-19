package com.dalai.llama.pbx.core.service.cdr;


import com.dalai.llama.pbx.core.domain.entity.cdr.CallRecord;
import com.dalai.llama.pbx.core.domain.enums.CallDirection;
import com.dalai.llama.pbx.core.domain.enums.CallStatus;
import com.dalai.llama.pbx.core.repository.cdr.CallRecordRepository;
import com.dalai.llama.pbx.core.service.cache.TenantConfigCacheService;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class CdrService {

    private final CallRecordRepository cdrRepository;
    private final TenantConfigCacheService configCache;

    // ═══════════════════════════════════════════════════════════
    // CREATE (call-start event from Kamailio)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public CallRecord createCdr(UUID tenantId, UUID subscriptionId, String callId,
                                CallDirection direction, String callerNumber,
                                String calleeNumber, String didNumber, String productCode) {

        // Idempotency — duplicate call-start events should not create duplicate CDRs
        Optional<CallRecord> existing = cdrRepository.findByCallId(callId);
        if (existing.isPresent()) {
            log.debug("CDR already exists for callId={}", callId);
            return existing.get();
        }

        CallRecord cdr = CallRecord.builder()
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .callId(callId)
                .direction(direction)
                .callerNumber(callerNumber)
                .calleeNumber(calleeNumber)
                .didNumber(didNumber)
                .productCode(productCode)
                .status(CallStatus.RINGING)
                .startTime(Instant.now())
                .build();

        cdr = cdrRepository.save(cdr);
        log.debug("CDR created: callId={}, tenant={}, direction={}", callId, tenantId, direction);
        return cdr;
    }

    // ═══════════════════════════════════════════════════════════
    // ANSWER (CHANNEL_ANSWER event from ESL)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void markAnswered(String callId, UUID agentId, UUID queueId) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            cdr.setStatus(CallStatus.ANSWERED);
            cdr.setAnswerTime(Instant.now());
            if (agentId != null) cdr.setAgentId(agentId);
            if (queueId != null) cdr.setQueueId(queueId);
            cdrRepository.save(cdr);
            log.debug("CDR answered: callId={}, agent={}", callId, agentId);
        });
    }

    // ═══════════════════════════════════════════════════════════
    // END (call-end event from Kamailio or CHANNEL_HANGUP from ESL)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void markEnded(String callId, String hangupCause) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            Instant endTime = Instant.now();
            cdr.setEndTime(endTime);
            cdr.setHangupCause(hangupCause);

            // Determine final status
            if (cdr.getAnswerTime() != null) {
                cdr.setStatus(CallStatus.COMPLETED);
            } else {
                // Never answered
                cdr.setStatus("NORMAL_CLEARING".equals(hangupCause)
                        ? CallStatus.MISSED : CallStatus.FAILED);
            }

            // Duration calculation
            if (cdr.getStartTime() != null) {
                long totalSeconds = endTime.getEpochSecond() - cdr.getStartTime().getEpochSecond();
                cdr.setDurationSeconds((int) Math.max(0, totalSeconds));

                if (cdr.getAnswerTime() != null) {
                    long talkSeconds = endTime.getEpochSecond() - cdr.getAnswerTime().getEpochSecond();
                    int billable = (int) Math.max(0, talkSeconds - 1); // 1s grace
                    cdr.setBillableSeconds(billable);
                }
            }

            // Billing cost calculation
            calculateCost(cdr);

            cdrRepository.save(cdr);
            log.debug("CDR ended: callId={}, status={}, duration={}s, cost={}",
                    callId, cdr.getStatus(), cdr.getDurationSeconds(), cdr.getCost());
        });
    }

    // ═══════════════════════════════════════════════════════════
    // UPDATES (recording, transcript, agent assignment)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void setRecordingUrl(String callId, String recordingUrl) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            cdr.setRecordingUrl(recordingUrl);
            cdrRepository.save(cdr);
        });
    }

    @Transactional
    public void setTranscript(String callId, String transcriptSummary, BigDecimal sentimentScore) {
        cdrRepository.findByCallId(callId).ifPresent(cdr -> {
            cdr.setTranscriptSummary(transcriptSummary);
            cdr.setSentimentScore(sentimentScore);
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
    // BILLING COST CALCULATION
    // ═══════════════════════════════════════════════════════════

    private void calculateCost(CallRecord cdr) {
        if (cdr.getBillableSeconds() == null || cdr.getBillableSeconds() <= 0) {
            cdr.setCost(BigDecimal.ZERO);
            cdr.setRatePerMinute(BigDecimal.ZERO);
            return;
        }

        // Lookup rate from tenant config
        BigDecimal rate = lookupRate(cdr.getTenantId(), cdr.getDirection());
        cdr.setRatePerMinute(rate);

        if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
            // Billable minutes = ceil(billableSeconds / 60)
            BigDecimal billableMinutes = BigDecimal.valueOf(cdr.getBillableSeconds())
                    .divide(BigDecimal.valueOf(60), 4, RoundingMode.CEILING);
            cdr.setCost(billableMinutes.multiply(rate).setScale(4, RoundingMode.HALF_UP));
        } else {
            cdr.setCost(BigDecimal.ZERO);
        }
    }

    private BigDecimal lookupRate(UUID tenantId, CallDirection direction) {
        try {
            Optional<Map<String, Object>> config = configCache.getConfig(tenantId);
            if (config.isPresent()) {
                String rateKey = direction == CallDirection.INBOUND
                        ? "ratePerMinuteInbound" : "ratePerMinuteOutbound";
                String rateKeySnake = direction == CallDirection.INBOUND
                        ? "rate_per_minute_inbound" : "rate_per_minute_outbound";

                Object rateObj = config.get().get(rateKey);
                if (rateObj == null) rateObj = config.get().get(rateKeySnake);

                if (rateObj instanceof Number n) {
                    return BigDecimal.valueOf(n.doubleValue());
                }
                if (rateObj instanceof String s && !s.isBlank()) {
                    return new BigDecimal(s);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to lookup rate for tenant {}: {}", tenantId, e.getMessage());
        }
        return BigDecimal.ZERO;
    }
}