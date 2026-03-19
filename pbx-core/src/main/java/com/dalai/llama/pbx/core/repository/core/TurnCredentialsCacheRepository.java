package com.dalai.llama.pbx.core.repository.core;

import com.dalai.llama.pbx.core.domain.entity.core.TurnCredentialsCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TURN credentials cache — DB audit trail for issued TURN creds.
 *
 * Primary fast path is Redis (TurnCredentialsRedisService).
 * This table is:
 *   - Audit trail of every TURN credential issued
 *   - Redis rebuild source if Redis is flushed
 *   - Cleanup target for ScheduledTasks (purge expired rows)
 *
 * Write path: TurnCredentialService generates creds → writes Redis + this table.
 * Read path: TurnController → reads from Redis first, falls back here.
 */
@Repository
public interface TurnCredentialsCacheRepository extends JpaRepository<TurnCredentialsCache, UUID> {

    List<TurnCredentialsCache> findByTenantId(UUID tenantId);

    /**
     * Most recent valid credential for a tenant — used as Redis fallback.
     */
    List<TurnCredentialsCache> findByTenantIdAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID tenantId, Instant now);

    /**
     * Purge expired entries.
     * Called by ScheduledTasks every hour.
     */
    @Modifying
    void deleteByExpiresAtBefore(Instant now);
}
