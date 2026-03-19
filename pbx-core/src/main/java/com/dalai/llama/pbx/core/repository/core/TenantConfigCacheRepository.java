package com.dalai.llama.pbx.core.repository.core;


import com.dalai.llama.pbx.core.domain.entity.core.TenantConfigCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant config cache — thin DB-backed cache of TenantApp from tenant-service.
 *
 * This is tier 2 in the 3-tier lookup (TenantConfigCacheService):
 *   1. Redis GET "tenant:config:{tenantId}"  ← fast path
 *   2. DB SELECT from this table             ← this repository
 *   3. HTTP GET tenant-service               ← slow path, writes back to 1 + 2
 *
 * Three lookup indexes for different call-auth entry points:
 *   - findByTenantId: when tenantId is already known (outbound, agent actions)
 *   - findByDidNumber: inbound call → resolve tenant from DID
 *   - findBySipDomain: FreeSWITCH directory → resolve tenant from SIP domain
 */
@Repository
public interface TenantConfigCacheRepository extends JpaRepository<TenantConfigCache, UUID> {

    Optional<TenantConfigCache> findByTenantId(UUID tenantId);

    Optional<TenantConfigCache> findByDidNumber(String didNumber);

    Optional<TenantConfigCache> findBySipDomain(String sipDomain);

    boolean existsByTenantId(UUID tenantId);

    @Modifying
    void deleteByTenantId(UUID tenantId);

    /**
     * Cleanup expired cache entries.
     * Called by ScheduledTasks every 5 minutes.
     */
    @Modifying
    void deleteByExpiresAtBefore(Instant now);
}