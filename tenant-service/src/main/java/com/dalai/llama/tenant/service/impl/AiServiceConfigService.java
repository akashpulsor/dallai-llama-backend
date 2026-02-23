package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI Service Configuration
 *
 * Configures AI integration for products:
 * - AI_CC: Full AI (greeting, routing, sentiment, transcription)
 * - CONV_IVR: Pure conversational AI
 * - VIRTUAL_RECEPTIONIST: AI receptionist with appointment/message
 * - OUTBOUND_DIALER: AMD, campaign AI
 *
 * Integration points:
 * - FastAGI: FreeSWITCH ↔ AI Service (agi://ai-service:4573)
 * - WebSocket: Real-time transcription
 * - REST API: Intent detection, TTS
 * - RTPEngine fork: Audio stream for real-time STT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceConfigService {

    private final RedisTemplate<String, String> redisTemplate;
    private final KubernetesConfigDiscoveryService configDiscovery;

    /**
     * Configure AI service for subscription
     */
    public void configureForSubscription(TenantApp app, PlanEntitlementResponse e) {
        log.info("Configuring AI Service for {} - product: {}", app.getSubscriptionId(), app.getProductCode());

        String key = "ai:config:" + app.getTenant().getId();
        boolean dedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());

        Map<String, String> config = new HashMap<>();

        // Product-specific AI configuration
        config.put("product", app.getProductCode());
        config.put("tenant_id", app.getTenant().getId().toString());
        config.put("subscription_id", app.getSubscriptionId().toString());

        // AI features from entitlements
        config.put("stt_enabled", String.valueOf(e.aiSttEnabled()));
        config.put("tts_enabled", String.valueOf(e.aiTtsEnabled()));
        config.put("bot_enabled", String.valueOf(e.aiBotEnabled()));
        config.put("routing_enabled", String.valueOf(e.aiRoutingEnabled()));
        config.put("sentiment_enabled", String.valueOf(e.aiSentimentEnabled()));
        config.put("noise_cancellation", String.valueOf(e.aiNoiseCancellationEnabled()));
        config.put("voice_morph", String.valueOf(e.aiVoiceMorphEnabled()));
        config.put("agent_assist", String.valueOf(e.aiAgentAssistEnabled()));

        // AI Service URLs
        config.put("ai_agi_url", configDiscovery.getAiAgiUrl(dedicated, app.getNamespace()));
        config.put("ai_http_url", configDiscovery.getAiServiceUrl(dedicated, app.getNamespace()));

        // Product-specific AI behavior
        configureProductAiBehavior(config, app.getProductCode(), e);

        // Usage limits
        config.put("tokens_per_month", String.valueOf(e.aiTokensPerMonth()));
        config.put("rate_per_minute", String.valueOf(e.aiRatePerMinute()));

        // Store in Redis
        redisTemplate.opsForHash().putAll(key, config);
        redisTemplate.expire(key, 30, TimeUnit.DAYS);

        log.info("AI Service configured for {} with {} features",
                app.getNamespace(), config.size());
    }

    private void configureProductAiBehavior(Map<String, String> config, String product, PlanEntitlementResponse e) {
        switch (product) {
            case "AI_CC" -> {
                config.put("ai_mode", "contact_center");
                config.put("greeting_type", "ai_dynamic");
                config.put("intent_detection", "true");
                config.put("queue_callback", "true");
                config.put("self_service", "true");
                config.put("agent_whisper", String.valueOf(e.whisperEnabled()));
                config.put("real_time_transcription", String.valueOf(e.aiSttEnabled()));
                config.put("sentiment_alerts", String.valueOf(e.aiSentimentEnabled()));
            }
            case "CONV_IVR" -> {
                config.put("ai_mode", "conversational_ivr");
                config.put("greeting_type", "ai_conversational");
                config.put("multi_turn", "true");
                config.put("context_memory", "true");
                config.put("intent_threshold", "0.7");
                config.put("clarification_enabled", "true");
                config.put("handoff_enabled", "true");
                config.put("multi_language", String.valueOf(e.ivrMultiLanguageEnabled()));
            }
            case "VIRTUAL_RECEPTIONIST" -> {
                config.put("ai_mode", "receptionist");
                config.put("greeting_type", "ai_receptionist");
                config.put("appointment_booking", "true");
                config.put("message_taking", "true");
                config.put("faq_enabled", "true");
                config.put("transfer_enabled", "true");
                config.put("business_hours_aware", "true");
                config.put("voicemail_transcription", String.valueOf(e.voicemailTranscriptionEnabled()));
            }
            case "OUTBOUND_DIALER" -> {
                config.put("ai_mode", "dialer");
                config.put("amd_enabled", String.valueOf(e.amdEnabled()));
                config.put("voicemail_detection", "true");
                config.put("campaign_script", "true");
                config.put("lead_qualification", "true");
                config.put("dnc_integration", String.valueOf(e.dncManagementEnabled()));
            }
            case "BASIC_PBX" -> {
                config.put("ai_mode", "basic");
                config.put("greeting_type", "static");
                config.put("voicemail_transcription", String.valueOf(e.voicemailTranscriptionEnabled()));
            }
            default -> {
                config.put("ai_mode", "disabled");
                config.put("greeting_type", "static");
            }
        }
    }

    /**
     * Get AI configuration for runtime (called by AI Service)
     */
    public Map<Object, Object> getAiConfig(String tenantId) {
        String key = "ai:config:" + tenantId;
        return redisTemplate.opsForHash().entries(key);
    }

    /**
     * Generate AGI endpoints configuration for FreeSWITCH
     */
    public String generateAgiEndpoints(TenantApp app, boolean dedicated) {
        String aiHost = dedicated
                ? "ai-service." + app.getNamespace() + ".svc.cluster.local"
                : "ai-service.dalaillama.svc.cluster.local";
        int agiPort = 4573;

        return String.format("""
                ; AI Service AGI Endpoints for %s
                ; Product: %s
                
                ; Greeting AGI - Dynamic AI greeting
                ; agi://%s:%d/greeting
                
                ; Conversation AGI - Multi-turn dialog
                ; agi://%s:%d/conversation
                
                ; Intent Detection AGI
                ; agi://%s:%d/intent
                
                ; Self-Service AGI
                ; agi://%s:%d/selfservice
                
                ; FAQ AGI
                ; agi://%s:%d/faq
                
                ; Receptionist AGI
                ; agi://%s:%d/receptionist
                
                ; Appointment Scheduler AGI
                ; agi://%s:%d/appointment
                
                ; Message Taker AGI
                ; agi://%s:%d/message
                
                ; Transcription AGI
                ; agi://%s:%d/transcribe
                
                ; AMD Result Handler AGI
                ; agi://%s:%d/amd-result
                
                ; Dialer Result AGI
                ; agi://%s:%d/dialer-result
                """,
                app.getNamespace(), app.getProductCode(),
                aiHost, agiPort, aiHost, agiPort, aiHost, agiPort,
                aiHost, agiPort, aiHost, agiPort, aiHost, agiPort,
                aiHost, agiPort, aiHost, agiPort, aiHost, agiPort,
                aiHost, agiPort, aiHost, agiPort
        );
    }

    public void removeSubscriptionConfig(String tenantId) {
        redisTemplate.delete("ai:config:" + tenantId);
        log.info("Removed AI config for tenant {}", tenantId);
    }
}