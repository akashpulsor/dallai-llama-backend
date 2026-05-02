package com.dalai.llama.pbx.core.controller.internal;

import com.dalai.llama.pbx.core.domain.entity.campaign.Bot;
import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignContact;
import com.dalai.llama.pbx.core.domain.enums.BotStatus;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import com.dalai.llama.pbx.core.redis.AiConfigRedisService;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import com.dalai.llama.pbx.core.redis.RtpEngineConfigRedisService;
import com.dalai.llama.pbx.core.repository.campaign.BotRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignContactRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import com.dalai.llama.pbx.core.service.cache.TenantConfigCacheService;
import com.dalai.llama.pbx.core.service.call.CallControlService;
import com.dalai.llama.pbx.core.service.cdr.CdrService;
import com.dalai.llama.pbx.core.service.storage.BlobStorageService;
import com.dalai.llama.pbx.core.websocket.WebSocketEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal AI Service controller — voice-brain (Pipecat) integration.
 * No JWT required — voice-brain runs inside the cluster (service-to-service).
 *
 * voice-brain calls PBX-Core at different stages of a call:
 *
 *   CALL START:
 *     GET  /internal/ai/config/{tenantId}         → fetch AI config + bot definition
 *
 *   DURING CALL (streaming):
 *     POST /internal/ai/transcript/live           → partial transcript → relay to Agent UI via STOMP
 *     POST /internal/ai/sentiment                 → sentiment score → relay to Supervisor UI
 *
 *   CALL END / ESCALATION:
 *     POST /internal/ai/transcript/final          → final transcript → save to CDR
 *     POST /internal/ai/escalation                → transfer to queue/agent or hangup via ESL
 *
 * Audio flow (NOT through PBX-Core):
 *   FreeSWITCH → mod_audio_stream → WebSocket → voice-brain (direct PCM audio)
 *   PBX-Core is the CONTROL PLANE, not the MEDIA PLANE.
 *
 * UI-facing AI endpoints live at /api/v1/ai/** (JWT-protected) — see AiApiController.
 */
@Slf4j
@RestController
@RequestMapping("/internal/ai")
@RequiredArgsConstructor
public class AiConfigController {

    private final TenantConfigCacheService configCache;
    private final AiConfigRedisService aiConfigRedis;
    private final BotRepository botRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignContactRepository campaignContactRepository;
    private final CdrService cdrService;
    private final ActiveCallTracker callTracker;
    private final CallControlService callControlService;
    private final WebSocketEventPublisher wsPublisher;
    private final BlobStorageService blobStorage;
    private final ObjectMapper objectMapper;
    private final RtpEngineConfigRedisService rtpEngineRedis;

    /**
     * In-memory buffer for live transcript utterances per call.
     * Accumulated during /transcript/live calls, assembled + flushed on /transcript/final.
     * Not persisted — if PBX-Core restarts mid-call, partial buffer is lost (acceptable:
     * ai-service still sends the full transcript on /transcript/final).
     */
    private final Map<String, List<Map<String, Object>>> transcriptBuffer = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════
    // 1. CONFIG — voice-brain fetches at call start (EXISTING)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/config/{tenantId}")
    public ResponseEntity<Map<String, Object>> getAiConfig(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String callId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String contactId) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", tenantId.toString());

        // Layer 1: Provisioned AI config from Redis (mode, AGI endpoints, limits)
        aiConfigRedis.getAsMap(tenantId).ifPresent(result::putAll);

        // Layer 2: Tenant config cache (entitlements, feature flags)
        Optional<Map<String, Object>> configOpt = configCache.getConfig(tenantId);
        if (configOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> tenantConfig = configOpt.get();
        result.put("product_code", tenantConfig.getOrDefault("productCode",
                tenantConfig.getOrDefault("product_code", "BASIC_PBX")));
        result.put("ai_enabled", tenantConfig.getOrDefault("aiBotEnabled",
                tenantConfig.getOrDefault("ai_bot_enabled", false)));
        result.put("stt_enabled", tenantConfig.getOrDefault("aiTranscriptionEnabled",
                tenantConfig.getOrDefault("ai_transcription_enabled", false)));
        result.put("sentiment_enabled", tenantConfig.getOrDefault("aiSentimentEnabled",
                tenantConfig.getOrDefault("ai_sentiment_enabled", false)));
        result.put("agent_assist_enabled", tenantConfig.getOrDefault("aiAgentAssistEnabled",
                tenantConfig.getOrDefault("ai_agent_assist_enabled", false)));
        result.put("noise_cancellation_enabled", tenantConfig.getOrDefault("aiNoiseCancellationEnabled",
                tenantConfig.getOrDefault("ai_noise_cancellation_enabled", false)));
        result.put("recording_enabled", tenantConfig.getOrDefault("recordingEnabled",
                tenantConfig.getOrDefault("recording_enabled", false)));

        // Layer 3: Bot config from DB
        Bot bot = null;
        if (botId != null && !botId.isBlank()) {
            bot = botRepository.findById(UUID.fromString(botId)).orElse(null);
        }
        if (bot == null) {
            bot = botRepository.findFirstByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, BotStatus.ACTIVE)
                    .orElse(null);
        }
        if (bot != null) {
            result.put("bot", buildBotPayload(bot));
        }

        // Layer 4: Call context (if callId provided)
        if (callId != null) {
            callTracker.getCallDetail(callId).ifPresent(detail -> {
                result.put("call_direction", detail.getOrDefault("direction", "INBOUND"));
                result.put("caller_number", detail.getOrDefault("caller_number", ""));
                result.put("callee_number", detail.getOrDefault("callee_number", ""));
            });
        }

        // Layer 5: Campaign + Contact context (outbound dialer personalization)
        if (campaignId != null && !campaignId.isBlank()) {
            campaignRepository.findById(UUID.fromString(campaignId)).ifPresent(campaign -> {
                Map<String, Object> campaignData = new LinkedHashMap<>();
                campaignData.put("campaign_id", campaign.getId().toString());
                campaignData.put("campaign_name", campaign.getName());
                campaignData.put("campaign_type", campaign.getCampaignType() != null ? campaign.getCampaignType().name() : "OUTBOUND");
                campaignData.put("description", campaign.getDescription());
                result.put("campaign", campaignData);

                // Override product_code for outbound dialer campaigns
                result.put("product_code", "OUTBOUND_DIALER");
                result.put("ai_enabled", true);
                result.put("stt_enabled", true);
            });
        }
        if (contactId != null && !contactId.isBlank()) {
            campaignContactRepository.findById(UUID.fromString(contactId)).ifPresent(contact -> {
                Map<String, Object> contactData = new LinkedHashMap<>();
                contactData.put("contact_id", contact.getId().toString());
                contactData.put("name", contact.getName());
                contactData.put("company", contact.getCompany());
                contactData.put("phone_number", contact.getPhoneNumber());
                contactData.put("email", contact.getEmail());
                contactData.put("custom_data", contact.getCustomData());
                contactData.put("attempt_count", contact.getAttemptCount());
                contactData.put("disposition", contact.getDisposition());
                result.put("contact", contactData);
            });
        }

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════
    // 2. LIVE TRANSCRIPT — voice-brain sends during call (EXISTING)
    // ═══════════════════════════════════════════════════════════

    /**
     * voice-brain sends partial transcript as STT produces words.
     * PBX-Core relays to Agent/Supervisor UI via STOMP WebSocket.
     *
     * Agent UI shows this as real-time captions alongside the call.
     */
    @PostMapping("/transcript/live")
    public ResponseEntity<Void> liveTranscript(@RequestBody Map<String, Object> body) {
        String callId = (String) body.get("call_id");
        String tenantIdStr = (String) body.get("tenant_id");
        if (callId == null || tenantIdStr == null) return ResponseEntity.badRequest().build();

        UUID tenantId = UUID.fromString(tenantIdStr);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "TRANSCRIPT_LIVE");
        event.put("call_id", callId);
        event.put("speaker", body.getOrDefault("speaker", "CALLER"));
        event.put("text", body.getOrDefault("text", ""));
        event.put("is_final", body.getOrDefault("is_final", false));
        event.put("confidence", body.getOrDefault("confidence", 0));
        event.put("language", body.getOrDefault("language", "en"));
        event.put("timestamp_ms", body.getOrDefault("timestamp_ms", 0));

        // Buffer final utterances for assembly on /transcript/final
        if (Boolean.TRUE.equals(body.getOrDefault("is_final", false))) {
            transcriptBuffer.computeIfAbsent(callId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(Map.of(
                            "speaker", body.getOrDefault("speaker", "CALLER"),
                            "text", body.getOrDefault("text", ""),
                            "timestamp_ms", body.getOrDefault("timestamp_ms", 0)
                    ));
        }

        wsPublisher.publishRaw(tenantId, "transcript", event);
        return ResponseEntity.ok().build();
    }

    // ═══════════════════════════════════════════════════════════
    // 3. FINAL TRANSCRIPT — voice-brain sends on call end (NEW)
    // ═══════════════════════════════════════════════════════════

    /**
     * Complete transcript saved to CDR for reporting/QA.
     */
    @PostMapping("/transcript/final")
    public ResponseEntity<Void> finalTranscript(@RequestBody Map<String, Object> body) {
        String callId = (String) body.get("call_id");
        String tenantIdStr = (String) body.get("tenant_id");
        if (callId == null) return ResponseEntity.badRequest().build();

        // Use ai-service-provided transcript if present, else fall back to in-memory buffer
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fullTranscript = (List<Map<String, Object>>) body.get("full_transcript");
        if (fullTranscript == null) {
            fullTranscript = transcriptBuffer.getOrDefault(callId, Collections.emptyList());
        }

        // Upload diarized transcript JSON to MinIO (per-tenant bucket)
        if (!fullTranscript.isEmpty() && tenantIdStr != null) {
            try {
                byte[] jsonBytes = objectMapper.writeValueAsBytes(Map.of(
                        "call_id", callId,
                        "tenant_id", tenantIdStr,
                        "utterances", fullTranscript
                ));
                String tenantSlug = resolveTenantSlug(tenantIdStr);
                String transcriptUrl = blobStorage.uploadTranscript(tenantSlug, callId, jsonBytes);
                if (transcriptUrl != null) {
                    cdrService.uploadTranscript(callId, null, null, new String(jsonBytes));
                    log.info("Transcript uploaded to MinIO: callId={} url={}", callId, transcriptUrl);
                }
            } catch (Exception e) {
                log.error("Failed to upload transcript for callId={}: {}", callId, e.getMessage());
            }
        }

        // Save summary text to CDR (for quick search without downloading JSON)
        String summary = (String) body.get("transcript_summary");
        if (summary != null) {
            cdrService.setTranscript(callId, summary, null);
        }

        Object aiMin = body.get("ai_minutes");
        if (aiMin instanceof Number n) {
            cdrService.setAiMinutes(callId, BigDecimal.valueOf(n.doubleValue()));
        }

        // Clear in-memory buffer for this call
        transcriptBuffer.remove(callId);

        log.info("Final transcript: callId={} utterances={} summary={}chars aiMin={}",
                callId, fullTranscript.size(), summary != null ? summary.length() : 0, aiMin);
        return ResponseEntity.ok().build();
    }

    // ═══════════════════════════════════════════════════════════
    // 4. SENTIMENT — voice-brain sends real-time scores (NEW)
    // ═══════════════════════════════════════════════════════════

    /**
     * Real-time sentiment relayed to Supervisor UI + saved to active call tracker.
     */
    @PostMapping("/sentiment")
    public ResponseEntity<Void> sentiment(@RequestBody Map<String, Object> body) {
        String callId = (String) body.get("call_id");
        String tenantIdStr = (String) body.get("tenant_id");
        if (callId == null || tenantIdStr == null) return ResponseEntity.badRequest().build();

        UUID tenantId = UUID.fromString(tenantIdStr);

        Object score = body.get("score");
        if (score instanceof Number n) {
            callTracker.updateField(callId, "sentiment_score", n.toString());
            // Persist sentiment score to CDR DB for reporting
            cdrService.setTranscript(callId, null, BigDecimal.valueOf(n.doubleValue()));
        }
        callTracker.updateField(callId, "sentiment_label",
                (String) body.getOrDefault("label", "NEUTRAL"));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "SENTIMENT_UPDATE");
        event.put("call_id", callId);
        event.put("score", body.getOrDefault("score", 0));
        event.put("label", body.getOrDefault("label", "NEUTRAL"));
        event.put("emotion_history", body.get("emotion_history"));

        wsPublisher.publishRaw(tenantId, "transcript", event);
        return ResponseEntity.ok().build();
    }

    // ═══════════════════════════════════════════════════════════
    // 5. ESCALATION — voice-brain triggers transfer/hangup (NEW)
    // ═══════════════════════════════════════════════════════════

    /**
     * AI escalation — transfer call to human agent/queue or hangup.
     *
     * Types: TRANSFER_QUEUE, TRANSFER_AGENT, TRANSFER_EXTERNAL, HANGUP
     */
    @PostMapping("/escalation")
    public ResponseEntity<Map<String, Object>> escalate(@RequestBody Map<String, Object> body) {
        String callId = (String) body.get("call_id");
        String tenantIdStr = (String) body.get("tenant_id");
        String escalationType = (String) body.get("escalation_type");
        String target = (String) body.get("target");
        String reason = (String) body.get("reason");

        if (callId == null || escalationType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "call_id and escalation_type required"));
        }

        UUID tenantId = tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;
        log.info("AI escalation: callId={} type={} target={} reason={}", callId, escalationType, target, reason);

        // Save AI context to CDR
        String transcript = (String) body.get("transcript_summary");
        if (transcript != null) cdrService.setTranscript(callId, transcript, null);
        Object sentiment = body.get("sentiment_score");
        if (sentiment instanceof Number n) cdrService.setTranscript(callId, null, BigDecimal.valueOf(n.doubleValue()));

        // Update call tracker
        callTracker.updateField(callId, "escalation_type", escalationType);
        callTracker.updateField(callId, "escalation_reason", reason != null ? reason : "");

        // Write bot intent/disposition back to campaign contact (if this is a campaign call)
        String intent = (String) body.get("intent");
        writeCampaignDisposition(callId, intent, reason);

        // Execute ESL command
        String result;
        try {
            String context = tenantId != null ? "tenant_" + tenantId : "default";
            result = switch (escalationType.toUpperCase()) {
                case "TRANSFER_QUEUE" -> callControlService.transfer(callId, target, context);
                case "TRANSFER_AGENT" -> callControlService.transfer(callId, target, context);
                case "TRANSFER_EXTERNAL" -> callControlService.transfer(callId, target, "default");
                case "HANGUP" -> callControlService.hangup(callId, "NORMAL_CLEARING");
                default -> "UNKNOWN_TYPE";
            };
        } catch (Exception e) {
            log.error("Escalation failed: callId={} — {}", callId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "escalation_failed", "message", e.getMessage()));
        }

        // Mark call for AI fork activation on subsequent CHANNEL_BRIDGE
        boolean forkPending = false;
        if (tenantId != null && !"HANGUP".equalsIgnoreCase(escalationType)) {
            if (rtpEngineRedis.isAiForkEnabled(tenantId)) {
                callTracker.updateField(callId, "ai_fork_pending", "true");
                forkPending = true;
                log.info("AI fork pending for escalated call: callId={} tenant={}", callId, tenantId);
            }
        }

        if (tenantId != null) {
            callTracker.updateStatus(callId, "TRANSFERRED");
            wsPublisher.callTransferred(tenantId, callId, target);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("call_id", callId);
        response.put("escalation_type", escalationType);
        response.put("target", target != null ? target : "");
        response.put("result", result);
        response.put("status", "executed");
        response.put("ai_fork_pending", forkPending);
        return ResponseEntity.ok(response);
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Write bot disposition back to campaign contact.
     * Called during AI escalation to persist the bot's detected intent
     * (e.g., "interested" → QUALIFIED, "not_interested" → NOT_QUALIFIED).
     * Looks up contactId from the call tracker (set by DialerEngine during origination).
     */
    private void writeCampaignDisposition(String callId, String intent, String reason) {
        if (intent == null || intent.isBlank()) return;
        try {
            Optional<Map<Object, Object>> detail = callTracker.getCallDetail(callId);
            if (detail.isEmpty()) return;

            Object contactIdObj = detail.get().get("contact_id");
            if (contactIdObj == null) return;

            UUID contactId = UUID.fromString(contactIdObj.toString());
            campaignContactRepository.findById(contactId).ifPresent(contact -> {
                contact.setDisposition(intent);

                String i = intent.toLowerCase();
                if (i.contains("interested") && !i.contains("not_interested")) {
                    contact.setStatus(ContactStatus.QUALIFIED);
                } else if (i.contains("not_interested") || i.contains("not interested")) {
                    contact.setStatus(ContactStatus.NOT_QUALIFIED);
                }

                campaignContactRepository.save(contact);
                log.info("Campaign disposition updated: contact={} intent={} status={}",
                        contactId, intent, contact.getStatus());
            });
        } catch (Exception e) {
            log.warn("Failed to write campaign disposition for callId={}: {}", callId, e.getMessage());
        }
    }

    /**
     * Resolve tenant slug (namespace) from config cache.
     * Used for per-tenant MinIO bucket naming: {prefix}-{slug}-{suffix}
     */
    private String resolveTenantSlug(String tenantIdStr) {
        try {
            UUID tenantId = UUID.fromString(tenantIdStr);
            return configCache.getConfig(tenantId)
                    .map(cfg -> {
                        Object ns = cfg.get("namespace");
                        return ns != null ? ns.toString() : tenantIdStr;
                    })
                    .orElse(tenantIdStr);
        } catch (Exception e) {
            log.warn("Failed to resolve tenant slug for {}: {}", tenantIdStr, e.getMessage());
            return tenantIdStr;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL — bot payload builder (EXISTING, unchanged)
    // ═══════════════════════════════════════════════════════════

    private Map<String, Object> buildBotPayload(Bot bot) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("bot_id", bot.getId().toString());
        b.put("name", bot.getName());
        b.put("system_prompt", bot.getSystemPrompt());
        b.put("greeting_message", bot.getGreetingMessage());
        b.put("goodbye_message", bot.getGoodbyeMessage());
        b.put("guidelines", bot.getGuidelines());
        b.put("allowed_intents", bot.getAllowedIntents());
        b.put("fallback_message", bot.getFallbackMessage());
        b.put("escalation_rules", bot.getEscalationRules());
        b.put("transfer_target", bot.getTransferTarget());
        b.put("transfer_type", bot.getTransferType() != null ? bot.getTransferType().name() : null);
        b.put("voice_provider", bot.getVoiceProvider());
        b.put("voice_id", bot.getVoiceId());
        b.put("voice_speed", bot.getVoiceSpeed());
        b.put("language", bot.getLanguage());
        b.put("max_turns", bot.getMaxTurns());
        b.put("max_duration_seconds", bot.getMaxDurationSeconds());
        b.put("dtmf_enabled", bot.getDtmfEnabled());
        b.put("barge_in_enabled", bot.getBargeInEnabled());
        b.put("sentiment_tracking", bot.getSentimentTracking());
        b.put("transcript_enabled", bot.getTranscriptEnabled());
        b.put("rag_enabled", bot.getRagEnabled());
        b.put("custom_data", bot.getCustomData());
        return b;
    }
}