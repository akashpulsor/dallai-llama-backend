package com.dalai.llama.pbx.core.controller.write;


import com.dalai.llama.pbx.core.redis.TenantConfigRedisService;
import com.dalai.llama.pbx.core.service.cache.TenantConfigCacheService;
import com.dalai.llama.pbx.core.service.turn.TurnCredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Cache management APIs — called by tenant-service after provisioning completes.
 *
 * Typical sequence:
 *   1. tenant-service writes all Kamailio/dialplan rows via other write controllers
 *   2. POST /cache/warm — pre-warm Redis with TenantApp snapshot
 *   3. POST /did-mapping — set DID→tenantId reverse index in Redis
 *   4. POST /turn — generate TURN creds and cache
 *
 * Also used for cache invalidation when tenant config changes.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/write/cache")
@RequiredArgsConstructor
public class CacheWarmController {

    private final TenantConfigCacheService configCacheService;
    private final TenantConfigRedisService redisService;
    private final TurnCredentialService turnCredentialService;

    /**
     * Pre-warm tenant config cache with full TenantApp snapshot.
     * tenant-service sends its TenantApp as JSON body after all provisioning.
     */
    @PostMapping("/warm")
    public ResponseEntity<Map<String, String>> warmCache(@RequestBody Map<String, Object> tenantAppSnapshot) {
        Object tidObj = tenantAppSnapshot.get("tenantId");
        if (tidObj == null) tidObj = tenantAppSnapshot.get("tenant_id");
        if (tidObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId required"));
        }

        UUID tenantId = UUID.fromString(tidObj.toString());
        configCacheService.writeToCache(tenantId, tenantAppSnapshot);

        log.info("Cache warmed for tenant {}", tenantId);
        return ResponseEntity.ok(Map.of("status", "warmed", "tenant_id", tenantId.toString()));
    }

    /**
     * Evict cache for a tenant — called when tenant config changes.
     * Next call to this tenant will trigger a fresh fetch from tenant-service.
     */
    @PostMapping("/evict/{tenantId}")
    public ResponseEntity<Map<String, String>> evictCache(@PathVariable UUID tenantId) {
        configCacheService.evict(tenantId);
        log.info("Cache evicted for tenant {}", tenantId);
        return ResponseEntity.ok(Map.of("status", "evicted", "tenant_id", tenantId.toString()));
    }

    /**
     * Set DID→tenantId reverse index in Redis.
     * Called once during provisioning — persists until explicit delete.
     * This is how inbound calls resolve the tenant from the DID number.
     */
    @PostMapping("/did-mapping")
    public ResponseEntity<Map<String, String>> setDidMapping(@RequestBody Map<String, String> body) {
        String didNumber = body.get("did_number");
        String tenantIdStr = body.get("tenant_id");

        if (didNumber == null || tenantIdStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "did_number and tenant_id required"));
        }

        UUID tenantId = UUID.fromString(tenantIdStr);
        redisService.mapDidToTenant(didNumber, tenantId);

        // Also set domain mapping if provided
        String sipDomain = body.get("sip_domain");
        if (sipDomain != null) {
            redisService.mapDomainToTenant(sipDomain, tenantId);
        }

        log.info("DID mapping set: {} → tenant {}", didNumber, tenantId);
        return ResponseEntity.ok(Map.of("status", "mapped", "did", didNumber, "tenant_id", tenantIdStr));
    }

    /**
     * Remove DID mapping — called during deprovision.
     */
    @DeleteMapping("/did-mapping/{didNumber}")
    public ResponseEntity<Void> removeDidMapping(@PathVariable String didNumber) {
        redisService.removeDidMapping(didNumber);
        log.info("DID mapping removed: {}", didNumber);
        return ResponseEntity.noContent().build();
    }

    /**
     * Generate and cache TURN credentials for a tenant.
     */
    @PostMapping("/turn")
    public ResponseEntity<Map<String, Object>> generateTurnCreds(@RequestBody Map<String, String> body) {
        String tenantSlug = body.get("tenant_slug");
        UUID tenantId = UUID.fromString(body.get("tenant_id"));
        boolean dedicated = "true".equalsIgnoreCase(body.get("dedicated"));
        int ttl = body.containsKey("ttl") ? Integer.parseInt(body.get("ttl")) : 86400;

        Map<String, Object> creds = turnCredentialService.generateAndStore(tenantSlug, tenantId, dedicated, ttl);
        return ResponseEntity.ok(creds);
    }
}