package com.dalai.llama.pbx.core.repository.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.DncEntry;
import com.dalai.llama.pbx.core.domain.enums.DncSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DNC (Do-Not-Call) list — tenant-scoped.
 *
 * Checked on TWO hot paths:
 *   1. CallAuthorizationService.authorizeOutbound() → reject call if on DNC
 *   2. DialerEngine → skip contact if on DNC before originating
 *
 * DNC entries can be permanent (expires_at = null) or temporary.
 * ScheduledTasks purges expired entries every hour.
 *
 * Write paths:
 *   - ContactController CRUD /api/v1/dnc
 *   - ContactService → bulk import from CSV
 *   - CallController → caller requests "do not call me again"
 */
@Repository
public interface DncEntryRepository extends JpaRepository<DncEntry, UUID> {

    /**
     * THE hot-path check — is this phone number on the DNC list?
     * Called on every outbound call authorization and every dialer contact pick.
     * Only matches non-expired entries (expires_at IS NULL = permanent, or > now).
     */
    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM DncEntry d " +
            "WHERE d.tenantId = :tenantId AND d.phoneNumber = :phoneNumber " +
            "AND (d.expiresAt IS NULL OR d.expiresAt > :now)")
    boolean isOnDncList(UUID tenantId, String phoneNumber, Instant now);

    // ── Listing ──

    Page<DncEntry> findByTenantId(UUID tenantId, Pageable pageable);

    List<DncEntry> findByTenantId(UUID tenantId);

    Page<DncEntry> findByTenantIdAndSource(UUID tenantId, DncSource source, Pageable pageable);

    Optional<DncEntry> findByTenantIdAndPhoneNumber(UUID tenantId, String phoneNumber);

    boolean existsByTenantIdAndPhoneNumber(UUID tenantId, String phoneNumber);

    // ── Counts ──

    long countByTenantId(UUID tenantId);

    // ── Delete / cleanup ──

    @Modifying
    void deleteByTenantIdAndPhoneNumber(UUID tenantId, String phoneNumber);

    /**
     * Purge expired DNC entries.
     * Called by ScheduledTasks every hour.
     * Only deletes entries with a non-null expires_at in the past.
     */
    @Modifying
    @Query("DELETE FROM DncEntry d WHERE d.expiresAt IS NOT NULL AND d.expiresAt < :now")
    int deleteExpiredEntries(Instant now);
}