package com.dalai.llama.pbx.core.service.turn;


import com.dalai.llama.pbx.core.domain.entity.core.TurnCredentialsCache;
import com.dalai.llama.pbx.core.redis.TurnCredentialsRedisService;
import com.dalai.llama.pbx.core.repository.core.TurnCredentialsCacheRepository;
import com.dalai.llama.pbx.core.util.HmacUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * TURN credential generation and management.
 *
 * CoTURN uses time-limited HMAC credentials (RFC 5389 long-term):
 *   username = "{expiry_unix_timestamp}:tenant_{slug}"
 *   password = Base64(HMAC-SHA1(username, shared_secret))
 *
 * Credentials are:
 *   1. Stored in Redis (TurnCredentialsRedisService) — fast path for Agent UI
 *   2. Stored in DB (TurnCredentialsCacheRepository) — audit trail + Redis rebuild
 *
 * Agent UI flow:
 *   GET /api/v1/turn/credentials/{tenantSlug}
 *   → TurnController → this service
 *   → Redis hit? return cached creds
 *   → Redis miss? generate new creds, store both, return
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnCredentialService {

    private final TurnCredentialsRedisService redisService;
    private final TurnCredentialsCacheRepository dbRepository;

    @Value("${coturn.secret:changeme}")
    private String turnSecret;

    @Value("${coturn.host:turn.dalaillama.in}")
    private String turnHost;

    @Value("${coturn.default-ttl:86400}")
    private int defaultTtl;

    /**
     * Get or generate TURN credentials for a tenant.
     * Redis-first — only generates new creds on miss.
     */
    public Map<String, Object> getCredentials(String tenantSlug, UUID tenantId, boolean dedicated) {
        // Redis fast path
        Optional<Map<Object, Object>> cached = redisService.get(tenantSlug);
        if (cached.isPresent()) {
            Map<String, Object> result = new LinkedHashMap<>();
            cached.get().forEach((k, v) -> result.put(k.toString(), v));
            return result;
        }

        // DB fallback — find valid (non-expired) credential
        List<TurnCredentialsCache> dbCreds = dbRepository.findByTenantIdAndExpiresAtAfterOrderByCreatedAtDesc(
                tenantId, Instant.now());
        if (!dbCreds.isEmpty()) {
            TurnCredentialsCache cred = dbCreds.get(0);
            // Warm Redis
            int remainingTtl = (int) Instant.now().until(cred.getExpiresAt(), ChronoUnit.SECONDS);
            redisService.store(tenantSlug, cred.getUsername(), cred.getPassword(),
                    cred.getTurnUrl(), cred.getTurnsUrl(), cred.getStunUrl(), remainingTtl);
            return buildResponse(cred);
        }

        // Generate new credentials
        return generateAndStore(tenantSlug, tenantId, dedicated, defaultTtl);
    }

    /**
     * Generate fresh TURN credentials — called during provisioning or on cache miss.
     *
     * @param ttlSeconds credential lifetime (3600 for runtime, 86400 for provisioning)
     */
    public Map<String, Object> generateAndStore(String tenantSlug, UUID tenantId,
                                                boolean dedicated, int ttlSeconds) {
        long expiryTimestamp = Instant.now().getEpochSecond() + ttlSeconds;
        String username = expiryTimestamp + ":tenant_" + tenantSlug;
        String password = HmacUtil.hmacSha1(username, turnSecret);

        // Resolve TURN URLs
        String turnUrl = dedicated
                ? String.format("turn:turn.%s.dalaillama.in:3478", tenantSlug)
                : String.format("turn:%s:3478", turnHost);
        String turnsUrl = dedicated
                ? String.format("turns:turn.%s.dalaillama.in:5349", tenantSlug)
                : String.format("turns:%s:5349", turnHost);
        String stunUrl = String.format("stun:%s:3478", turnHost);

        Instant expiresAt = Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS);

        // Store in Redis
        redisService.store(tenantSlug, username, password, turnUrl, turnsUrl, stunUrl, ttlSeconds);

        // Store in DB (audit trail)
        TurnCredentialsCache dbEntry = TurnCredentialsCache.builder()
                .tenantId(tenantId)
                .username(username)
                .password(password)
                .turnUrl(turnUrl)
                .turnsUrl(turnsUrl)
                .stunUrl(stunUrl)
                .ttl(ttlSeconds)
                .expiresAt(expiresAt)
                .build();
        dbRepository.save(dbEntry);

        log.info("Generated TURN creds for {} (TTL={}s, dedicated={})", tenantSlug, ttlSeconds, dedicated);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("password", password);
        result.put("turn_url", turnUrl);
        result.put("turns_url", turnsUrl);
        result.put("stun_url", stunUrl);
        result.put("ttl", ttlSeconds);
        return result;
    }

    private Map<String, Object> buildResponse(TurnCredentialsCache cred) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", cred.getUsername());
        result.put("password", cred.getPassword());
        result.put("turn_url", cred.getTurnUrl());
        result.put("turns_url", cred.getTurnsUrl());
        result.put("stun_url", cred.getStunUrl());
        result.put("ttl", cred.getTtl());
        return result;
    }
}