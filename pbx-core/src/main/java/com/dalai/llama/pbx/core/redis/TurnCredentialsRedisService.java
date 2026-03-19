package com.dalai.llama.pbx.core.redis;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * TURN credentials Redis cache — fast path for Agent UI WebRTC setup.
 *
 * Keys:
 *   turn:cred:{tenantSlug} → HASH {
 *     username:  "{expiry_timestamp}:tenant_{slug}"
 *     password:  Base64(HMAC-SHA1(username, coturn_shared_secret))
 *     turn_url:  "turn:turn.dalaillama.in:3478"
 *     turns_url: "turns:turn.dalaillama.in:5349"
 *     stun_url:  "stun:turn.dalaillama.in:3478"
 *     ttl:       "3600"
 *   }
 *
 * TTL matches the credential lifetime (typically 1h for runtime, 24h for provisioning).
 *
 * Read path:
 *   TurnController GET /api/v1/turn/credentials/{tenantSlug} → this service
 *   Falls back to TurnCredentialsCacheRepository if Redis miss.
 *
 * Write path:
 *   TurnCredentialService → generates creds, writes here + DB audit table.
 *   CacheWarmController → tenant-service calls during provisioning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnCredentialsRedisService {

    private final StringRedisTemplate redis;

    private static final String PREFIX = "turn:cred:";

    /**
     * Store TURN credentials in Redis with TTL matching credential lifetime.
     */
    public void store(String tenantSlug, String username, String password,
                      String turnUrl, String turnsUrl, String stunUrl, int ttlSeconds) {
        String key = PREFIX + tenantSlug;

        redis.opsForHash().putAll(key, Map.of(
                "username", username,
                "password", password,
                "turn_url", turnUrl != null ? turnUrl : "",
                "turns_url", turnsUrl != null ? turnsUrl : "",
                "stun_url", stunUrl != null ? stunUrl : "",
                "ttl", String.valueOf(ttlSeconds)
        ));
        redis.expire(key, Duration.ofSeconds(ttlSeconds));

        log.debug("Stored TURN creds for {} (TTL {}s)", tenantSlug, ttlSeconds);
    }

    /**
     * Fetch TURN credentials — returns empty if not cached or expired.
     */
    public Optional<Map<Object, Object>> get(String tenantSlug) {
        String key = PREFIX + tenantSlug;
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(entries);
    }

    /**
     * Check if credentials exist and are not expired.
     */
    public boolean exists(String tenantSlug) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + tenantSlug));
    }

    /**
     * Remove credentials — called during deprovision.
     */
    public void remove(String tenantSlug) {
        redis.delete(PREFIX + tenantSlug);
        log.debug("Removed TURN creds for {}", tenantSlug);
    }
}