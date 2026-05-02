package com.dalai.llama.pbx.core.controller.ai;

import com.dalai.llama.pbx.core.domain.entity.campaign.Bot;
import com.dalai.llama.pbx.core.domain.enums.BotStatus;
import com.dalai.llama.pbx.core.redis.AiConfigRedisService;
import com.dalai.llama.pbx.core.repository.campaign.BotRepository;
import com.dalai.llama.pbx.core.service.cache.TenantConfigCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Public AI endpoints — JWT-protected, called by UI (Admin/Supervisor/Agent).
 *
 * Read-only view of the AI configuration for a tenant.
 * Mutations (bot CRUD) are handled by BotController at /api/v1/bots.
 *
 * Internal voice-brain endpoints live at /internal/ai/** — see AiConfigController.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiApiController {

    private final TenantConfigCacheService configCache;
    private final AiConfigRedisService aiConfigRedis;
    private final BotRepository botRepository;

    /**
     * GET /api/v1/ai/config/{tenantId}
     * Returns the merged AI configuration for a tenant (feature flags + active bot).
     * Used by Admin UI to display AI settings, and Agent UI for agent-assist config.
     */
    @GetMapping("/config/{tenantId}")
    public ResponseEntity<Map<String, Object>> getAiConfig(@PathVariable UUID tenantId) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", tenantId.toString());

        // Layer 1: Provisioned AI config from Redis
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

        // Layer 3: Active bot (summary only — full CRUD via /api/v1/bots)
        Bot bot = botRepository.findFirstByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, BotStatus.ACTIVE)
                .orElse(null);
        if (bot != null) {
            Map<String, Object> botSummary = new LinkedHashMap<>();
            botSummary.put("bot_id", bot.getId().toString());
            botSummary.put("name", bot.getName());
            botSummary.put("language", bot.getLanguage());
            botSummary.put("voice_provider", bot.getVoiceProvider());
            botSummary.put("voice_id", bot.getVoiceId());
            botSummary.put("sentiment_tracking", bot.getSentimentTracking());
            botSummary.put("transcript_enabled", bot.getTranscriptEnabled());
            result.put("active_bot", botSummary);
        }

        return ResponseEntity.ok(result);
    }
}
