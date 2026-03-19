package com.dalai.llama.pbx.core.redis;


import com.dalai.llama.pbx.core.dto.request.provisioning.RtpEngineConfigRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-tenant RTPEngine configuration stored in Redis.
 *
 * Key: rtpengine:config:{tenantId} → HASH
 *
 * Read by CallAuthorizationService — when authorizing an inbound call,
 * the response includes RTPEngine hints (codecs, recording flags, AI fork target)
 * that Kamailio uses in rtpengine_manage() directives.
 *
 * Written during provisioning by ProvisioningService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RtpEngineConfigRedisService {

    private final StringRedisTemplate redis;

    private static final String PREFIX = "rtpengine:config:";

    public void store(UUID tenantId, RtpEngineConfigRequest request) {
        String key = PREFIX + tenantId;
        Map<String, String> config = new HashMap<>();
        config.put("namespace", safe(request.getNamespace()));
        config.put("codecs", safe(request.getCodecs()));
        config.put("recording_enabled", String.valueOf(request.getRecordingEnabled()));
        config.put("recording_path", safe(request.getRecordingPath()));
        config.put("max_channels", String.valueOf(request.getMaxChannels()));
        config.put("transcoding_enabled", String.valueOf(request.getTranscodingEnabled()));
        config.put("ai_fork_enabled", String.valueOf(request.getAiForkEnabled()));
        config.put("ai_fork_target", safe(request.getAiForkTarget()));
        config.put("dedicated", String.valueOf(request.getDedicatedInfrastructure()));

        redis.opsForHash().putAll(key, config);
        log.debug("Stored RTPEngine config for tenant {}", tenantId);
    }

    public Optional<Map<Object, Object>> get(UUID tenantId) {
        Map<Object, Object> entries = redis.opsForHash().entries(PREFIX + tenantId);
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries);
    }

    public void remove(UUID tenantId) {
        redis.delete(PREFIX + tenantId);
        log.debug("Removed RTPEngine config for tenant {}", tenantId);
    }

    private String safe(String val) {
        return val != null ? val : "";
    }
}