package com.dalai.llama.pbx.core.controller.internal;

import com.dalai.llama.pbx.core.domain.entity.campaign.Bot;
import com.dalai.llama.pbx.core.domain.enums.BotStatus;
import com.dalai.llama.pbx.core.redis.AiConfigRedisService;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import com.dalai.llama.pbx.core.repository.campaign.BotRepository;
import com.dalai.llama.pbx.core.service.cache.TenantConfigCacheService;
import com.dalai.llama.pbx.core.service.call.CallControlService;
import com.dalai.llama.pbx.core.service.cdr.CdrService;
import com.dalai.llama.pbx.core.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * AI Service controller — voice-brain (Pipecat) integration.
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
 */
@Slf4j
@RestController
@RequestMapping("/internal/ai")
@RequiredArgsConstructor
public class AiConfigController {

    private final TenantConfigCacheService configCache;
    private final AiConfigRedisService aiConfigRedis;
    private final BotRepository botRepository;
    private final CdrService cdrService;
    private final ActiveCallTracker callTracker;
    private final CallControlService callControlService;
    private final WebSocketEventPublisher wsPublisher;

    // ═══════════════════════════════════════════════════════════
    // 1. CONFIG — voice-brain fetches at call start (EXISTING)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/config/{tenantId}")
    public ResponseEntity<Map<String, Object>> getAiConfig(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String callId) {

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

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════
    // 2. LIVE TRANSCRIPT — voice-brain streams partial STT (NEW)
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
        if (callId == null) return ResponseEntity.badRequest().build();

        String summary = (String) body.get("transcript_summary");
        if (summary != null) {
            cdrService.setTranscript(callId, summary, null);
        }

        Object aiMin = body.get("ai_minutes");
        if (aiMin instanceof Number n) {
            cdrService.setAiMinutes(callId, BigDecimal.valueOf(n.doubleValue()));
        }

        log.info("Final transcript: callId={} summary={}chars aiMin={}",
                callId, summary != null ? summary.length() : 0, aiMin);
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

        if (tenantId != null) {
            callTracker.updateStatus(callId, "TRANSFERRED");
            wsPublisher.callTransferred(tenantId, callId, target);
        }

        return ResponseEntity.ok(Map.of(
                "call_id", callId, "escalation_type", escalationType,
                "target", target != null ? target : "", "result", result, "status", "executed"
        ));
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