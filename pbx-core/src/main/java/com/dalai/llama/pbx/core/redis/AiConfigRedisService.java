package com.dalai.llama.pbx.core.redis;


import com.dalai.llama.pbx.core.dto.request.provisioning.AiConfigRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-tenant AI configuration stored in Redis.
 *
 * Key: ai:config:{tenantId} → JSON string (serialized AiConfigRequest)
 *
 * Read by AiConfigController GET /internal/ai/config/{tenantId} — voice-brain
 * fetches this when starting a Pipecat pipeline for a call. The Redis value
 * contains the complete AI mode config (prompts, AGI endpoints, feature flags).
 *
 * Also read by CallAuthorizationService to include AI hints in auth response
 * (so Kamailio knows whether to add AI-related SIP headers).
 *
 * Written during provisioning by ProvisioningService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigRedisService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String PREFIX = "ai:config:";

    /**
     * Store full AI config as JSON. No TTL — lives until explicit delete on deprovision.
     */
    public void store(UUID tenantId, AiConfigRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            redis.opsForValue().set(PREFIX + tenantId, json);
            log.debug("Stored AI config for tenant {} [mode={}]", tenantId, request.getAiMode());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AI config for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Read AI config — returns deserialized AiConfigRequest or empty.
     */
    public Optional<AiConfigRequest> get(UUID tenantId) {
        String json = redis.opsForValue().get(PREFIX + tenantId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, AiConfigRequest.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize AI config for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read as raw Map — used by AiConfigController to build the response
     * without needing to deserialize into the provisioning DTO.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getAsMap(UUID tenantId) {
        String json = redis.opsForValue().get(PREFIX + tenantId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, Map.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize AI config map for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    public void remove(UUID tenantId) {
        redis.delete(PREFIX + tenantId);
        log.debug("Removed AI config for tenant {}", tenantId);
    }

    public boolean exists(UUID tenantId) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + tenantId));
    }
}