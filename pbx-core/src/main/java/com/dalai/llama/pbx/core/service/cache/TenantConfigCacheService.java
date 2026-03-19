package com.dalai.llama.pbx.core.service.cache;


import com.dalai.llama.pbx.core.client.TenantServiceClient;
import com.dalai.llama.pbx.core.domain.entity.core.TenantConfigCache;
import com.dalai.llama.pbx.core.redis.TenantConfigRedisService;
import com.dalai.llama.pbx.core.repository.core.TenantConfigCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 3-tier tenant config lookup — THE bridge between PBX-Core and tenant-service.
 *
 * PBX-Core does NOT own tenant config (TenantApp lives in tenant-service).
 * This service provides a fast, cached view of it for the call-auth hot path.
 *
 * Tier 1: Redis GET "tenant:config:{tenantId}"
 *         → HIT: return immediately (~0.5ms)
 *
 * Tier 2: DB SELECT from tenant_config_cache WHERE tenant_id = ?
 *         → HIT + not expired: return + warm Redis (~2ms)
 *
 * Tier 3: HTTP GET tenant-service /api/v1/tenant-apps/by-tenant/{tenantId}
 *         → Write to Redis + DB, return (~50-200ms)
 *
 * Cache invalidation:
 *   - TTL-based: Redis 5min, DB 5min (configurable)
 *   - Explicit: tenant-service calls POST /api/v1/write/cache/evict/{tenantId}
 *   - Warm: tenant-service calls POST /api/v1/write/cache/warm after provisioning
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantConfigCacheService {

    private final TenantConfigRedisService redisService;
    private final TenantConfigCacheRepository cacheRepository;
    private final TenantServiceClient tenantServiceClient;

    @Value("${pbxcore.cache.tenant-config-ttl-minutes:5}")
    private long ttlMinutes;

    // ═══════════════════════════════════════════════════════════
    // Lookup by tenantId (outbound calls, agent actions)
    // ═══════════════════════════════════════════════════════════

    public Optional<Map<String, Object>> getConfig(UUID tenantId) {
        // Tier 1: Redis
        Optional<Map<String, Object>> cached = redisService.getConfig(tenantId);
        if (cached.isPresent()) {
            log.trace("Cache HIT (Redis) for tenant {}", tenantId);
            return cached;
        }

        // Tier 2: DB
        Optional<TenantConfigCache> dbCache = cacheRepository.findByTenantId(tenantId);
        if (dbCache.isPresent() && dbCache.get().getExpiresAt().isAfter(Instant.now())) {
            Map<String, Object> snapshot = dbCache.get().getConfigSnapshot();
            redisService.cacheConfig(tenantId, snapshot); // warm Redis
            log.debug("Cache HIT (DB) for tenant {} — warmed Redis", tenantId);
            return Optional.of(snapshot);
        }

        // Tier 3: HTTP → tenant-service
        log.debug("Cache MISS for tenant {} — fetching from tenant-service", tenantId);
        Optional<Map<String, Object>> remote = tenantServiceClient.getTenantAppByTenantId(tenantId);
        remote.ifPresent(config -> writeToCache(tenantId, config));
        return remote;
    }

    // ═══════════════════════════════════════════════════════════
    // Lookup by DID (inbound calls — most common entry point)
    // ═══════════════════════════════════════════════════════════

    public Optional<Map<String, Object>> getConfigByDid(String didNumber) {
        // Try Redis DID→tenantId index
        Optional<UUID> tenantId = redisService.getTenantByDid(didNumber);
        if (tenantId.isPresent()) {
            return getConfig(tenantId.get());
        }

        // Try DB cache (has did_number index)
        Optional<TenantConfigCache> dbCache = cacheRepository.findByDidNumber(didNumber);
        if (dbCache.isPresent() && dbCache.get().getExpiresAt().isAfter(Instant.now())) {
            UUID tid = dbCache.get().getTenantId();
            redisService.mapDidToTenant(didNumber, tid); // warm DID index
            return getConfig(tid);
        }

        // HTTP fallback — fetch by DID
        log.debug("DID {} not in cache — fetching from tenant-service", didNumber);
        Optional<Map<String, Object>> remote = tenantServiceClient.getTenantAppByDid(didNumber);
        remote.ifPresent(config -> {
            Object tidObj = config.get("tenantId");
            if (tidObj == null) tidObj = config.get("tenant_id");
            if (tidObj != null) {
                UUID tid = UUID.fromString(tidObj.toString());
                writeToCache(tid, config);
                redisService.mapDidToTenant(didNumber, tid);
            }
        });
        return remote;
    }

    // ═══════════════════════════════════════════════════════════
    // Lookup by SIP domain (FreeSWITCH directory)
    // ═══════════════════════════════════════════════════════════

    public Optional<Map<String, Object>> getConfigByDomain(String sipDomain) {
        Optional<UUID> tenantId = redisService.getTenantByDomain(sipDomain);
        if (tenantId.isPresent()) {
            return getConfig(tenantId.get());
        }

        Optional<TenantConfigCache> dbCache = cacheRepository.findBySipDomain(sipDomain);
        if (dbCache.isPresent() && dbCache.get().getExpiresAt().isAfter(Instant.now())) {
            UUID tid = dbCache.get().getTenantId();
            redisService.mapDomainToTenant(sipDomain, tid);
            return getConfig(tid);
        }

        return Optional.empty(); // No HTTP fallback for domain — must be provisioned first
    }

    // ═══════════════════════════════════════════════════════════
    // Write (called by CacheWarmController + internal on cache miss)
    // ═══════════════════════════════════════════════════════════

    public void writeToCache(UUID tenantId, Map<String, Object> configSnapshot) {
        try {
            Instant expiresAt = Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES);

            TenantConfigCache cache = cacheRepository.findByTenantId(tenantId)
                    .orElse(TenantConfigCache.builder().tenantId(tenantId).build());

            cache.setSubscriptionId(safeUuid(configSnapshot, "subscriptionId", "subscription_id"));
            cache.setDidNumber(safeString(configSnapshot, "didNumber", "did_number"));
            cache.setSipDomain(safeStringRequired(configSnapshot, "sipEndpointDomain", "sip_endpoint_domain", "sip.dalaillama.in"));
            cache.setProductCode(safeStringRequired(configSnapshot, "productCode", "product_code", "BASIC_PBX"));
            cache.setNamespace(safeStringRequired(configSnapshot, "namespace", "namespace", "default"));
            cache.setMaxChannels(safeInt(configSnapshot, "maxChannels", "max_channels", 30));
            cache.setMaxInbound(safeIntOrNull(configSnapshot, "channelInbound", "channel_inbound"));
            cache.setMaxOutbound(safeIntOrNull(configSnapshot, "channelOutbound", "channel_outbound"));
            cache.setAiEnabled(safeBool(configSnapshot, "aiBotEnabled", "ai_bot_enabled", false));
            cache.setRecordingEnabled(safeBool(configSnapshot, "recordingEnabled", "recording_enabled", false));
            cache.setDeploymentModel(safeStringRequired(configSnapshot, "deploymentModel", "deployment_model", "SHARED"));
            cache.setStatus("ACTIVE");
            cache.setConfigSnapshot(configSnapshot);
            cache.setExpiresAt(expiresAt);

            cacheRepository.save(cache);
            redisService.cacheConfig(tenantId, configSnapshot);

            log.debug("Wrote cache for tenant {} (expires {})", tenantId, expiresAt);
        } catch (Exception e) {
            log.error("Failed to write cache for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Evict (tenant-service calls on config change)
    // ═══════════════════════════════════════════════════════════

    public void evict(UUID tenantId) {
        redisService.evictConfig(tenantId);
        cacheRepository.deleteByTenantId(tenantId);
        log.info("Evicted cache for tenant {}", tenantId);
    }

    public void evictAll(UUID tenantId, String didNumber, String sipDomain) {
        redisService.evictAll(tenantId, didNumber, sipDomain);
        cacheRepository.deleteByTenantId(tenantId);
        log.info("Evicted all cache entries for tenant {}", tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers — handle both camelCase and snake_case keys
    // (TenantApp from tenant-service might be either depending on
    //  Jackson config on that side)
    // ═══════════════════════════════════════════════════════════

    private UUID safeUuid(Map<String, Object> m, String camel, String snake) {
        Object v = m.get(camel);
        if (v == null) v = m.get(snake);
        if (v == null) return null;
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    private String safeString(Map<String, Object> m, String camel, String snake) {
        Object v = m.get(camel);
        if (v == null) v = m.get(snake);
        return v != null ? v.toString() : null;
    }

    private String safeStringRequired(Map<String, Object> m, String camel, String snake, String def) {
        String v = safeString(m, camel, snake);
        return v != null ? v : def;
    }

    private int safeInt(Map<String, Object> m, String camel, String snake, int def) {
        Object v = m.get(camel);
        if (v == null) v = m.get(snake);
        return v instanceof Number n ? n.intValue() : def;
    }

    private Integer safeIntOrNull(Map<String, Object> m, String camel, String snake) {
        Object v = m.get(camel);
        if (v == null) v = m.get(snake);
        return v instanceof Number n ? n.intValue() : null;
    }

    private boolean safeBool(Map<String, Object> m, String camel, String snake, boolean def) {
        Object v = m.get(camel);
        if (v == null) v = m.get(snake);
        return v instanceof Boolean b ? b : def;
    }
}