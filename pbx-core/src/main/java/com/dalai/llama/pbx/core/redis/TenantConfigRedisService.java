package com.dalai.llama.pbx.core.redis;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tier-1 (fastest) cache for tenant configuration.
 *
 * Keys:
 *   tenant:config:{tenantId}   → JSON snapshot of TenantApp (TTL 5min)
 *   tenant:did:{didNumber}     → tenantId string (no TTL — set during provisioning)
 *   tenant:domain:{sipDomain}  → tenantId string (no TTL — set during provisioning)
 *
 * Lookup flow (TenantConfigCacheService orchestrates):
 *   1. Redis GET tenant:config:{tenantId}       ← THIS SERVICE
 *   2. DB SELECT from tenant_config_cache       ← TenantConfigCacheRepository
 *   3. HTTP GET tenant-service                  ← TenantServiceClient
 *
 * Inbound call entry point:
 *   Kamailio sends DID number → Redis GET tenant:did:{did} → returns tenantId
 *   → then Redis GET tenant:config:{tenantId} → returns full config
 *
 * Write paths:
 *   - CacheWarmController POST /api/v1/write/cache/warm → cacheConfig + mapDidToTenant
 *   - TenantConfigCacheService on cache miss → cacheConfig (write-through)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantConfigRedisService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final Duration CONFIG_TTL = Duration.ofMinutes(5);
    private static final String CONFIG_PREFIX = "tenant:config:";
    private static final String DID_PREFIX = "tenant:did:";
    private static final String DOMAIN_PREFIX = "tenant:domain:";

    // ═══════════════════════════════════════════════════════════
    // Tenant Config (full TenantApp snapshot as JSON)
    // ═══════════════════════════════════════════════════════════

    public void cacheConfig(UUID tenantId, Map<String, Object> configSnapshot) {
        try {
            String json = objectMapper.writeValueAsString(configSnapshot);
            redis.opsForValue().set(CONFIG_PREFIX + tenantId, json, CONFIG_TTL);
            log.debug("Cached tenant config: {}", tenantId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize tenant config for {}", tenantId, e);
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getConfig(UUID tenantId) {
        String json = redis.opsForValue().get(CONFIG_PREFIX + tenantId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, Map.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize tenant config for {}", tenantId, e);
            return Optional.empty();
        }
    }

    public void evictConfig(UUID tenantId) {
        redis.delete(CONFIG_PREFIX + tenantId);
        log.debug("Evicted tenant config: {}", tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // DID → tenantId reverse index (no TTL — provisioned once)
    // ═══════════════════════════════════════════════════════════

    /**
     * Set during provisioning. Inbound calls resolve tenantId from DID.
     * No TTL — lives until explicit delete on deprovision.
     */
    public void mapDidToTenant(String didNumber, UUID tenantId) {
        redis.opsForValue().set(DID_PREFIX + didNumber, tenantId.toString());
        log.debug("Mapped DID {} → tenant {}", didNumber, tenantId);
    }

    public Optional<UUID> getTenantByDid(String didNumber) {
        String val = redis.opsForValue().get(DID_PREFIX + didNumber);
        return val != null ? Optional.of(UUID.fromString(val)) : Optional.empty();
    }

    public void removeDidMapping(String didNumber) {
        redis.delete(DID_PREFIX + didNumber);
    }

    // ═══════════════════════════════════════════════════════════
    // SIP Domain → tenantId reverse index (no TTL)
    // ═══════════════════════════════════════════════════════════

    public void mapDomainToTenant(String sipDomain, UUID tenantId) {
        redis.opsForValue().set(DOMAIN_PREFIX + sipDomain, tenantId.toString());
        log.debug("Mapped domain {} → tenant {}", sipDomain, tenantId);
    }

    public Optional<UUID> getTenantByDomain(String sipDomain) {
        String val = redis.opsForValue().get(DOMAIN_PREFIX + sipDomain);
        return val != null ? Optional.of(UUID.fromString(val)) : Optional.empty();
    }

    public void removeDomainMapping(String sipDomain) {
        redis.delete(DOMAIN_PREFIX + sipDomain);
    }

    // ═══════════════════════════════════════════════════════════
    // Bulk eviction (tenant deprovision)
    // ═══════════════════════════════════════════════════════════

    /**
     * Remove all Redis keys for a tenant.
     * Called during deprovision via CacheWarmController.
     */
    public void evictAll(UUID tenantId, String didNumber, String sipDomain) {
        evictConfig(tenantId);
        if (didNumber != null) removeDidMapping(didNumber);
        if (sipDomain != null) removeDomainMapping(sipDomain);
        log.info("Evicted all Redis keys for tenant {}", tenantId);
    }
}