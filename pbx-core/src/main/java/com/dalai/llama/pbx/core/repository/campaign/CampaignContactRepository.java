package com.dalai.llama.pbx.core.repository.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignContact;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Campaign contacts — outbound dial list with dialing state machine.
 *
 * State machine: PENDING → DIALING → CONNECTED/NO_ANSWER/BUSY/FAILED → COMPLETED/SKIPPED
 *                                   ↑                    │
 *                                   └── retry delay ─────┘ (back to PENDING with next_attempt_at)
 *
 * Read paths:
 *   - DialerEngine → findNextDialable() picks the next contact to call
 *   - ContactController → list contacts for a campaign
 *   - CampaignController → stats (count by status)
 *
 * Write paths:
 *   - ContactController → import contacts (bulk INSERT)
 *   - DialerEngine → status transitions (PENDING→DIALING→result)
 *   - ContactService → DNC marking, disposition updates
 */
@Repository
public interface CampaignContactRepository extends JpaRepository<CampaignContact, UUID> {

    // ── Listing ──

    Page<CampaignContact> findByCampaignId(UUID campaignId, Pageable pageable);

    Page<CampaignContact> findByCampaignIdAndStatus(
            UUID campaignId, ContactStatus status, Pageable pageable);

    // ── DialerEngine: pick next contact to dial ──

    /**
     * THE dialer query — finds the next contact to call:
     *   - Status is PENDING, NO_ANSWER, or BUSY (retryable)
     *   - next_attempt_at is null (first attempt) or in the past (retry time reached)
     *   - Ordered by priority DESC (highest first), then created_at ASC (FIFO within same priority)
     *
     * DialerEngine calls this with Pageable.ofSize(1) to pick one contact at a time,
     * or Pageable.ofSize(N) for batch origination in PREDICTIVE mode.
     */
    @Query("SELECT c FROM CampaignContact c WHERE c.campaign.id = :campaignId " +
            "AND c.status IN ('PENDING', 'NO_ANSWER', 'BUSY') " +
            "AND (c.nextAttemptAt IS NULL OR c.nextAttemptAt <= :now) " +
            "ORDER BY c.priority DESC, c.createdAt ASC")
    Page<CampaignContact> findNextDialable(UUID campaignId, Instant now, Pageable pageable);

    // ── Duplicate / DNC checks ──

    boolean existsByCampaignIdAndPhoneNumber(UUID campaignId, String phoneNumber);

    Optional<CampaignContact> findByCampaignIdAndPhoneNumber(UUID campaignId, String phoneNumber);

    // ── Counts (campaign stats) ──

    long countByCampaignId(UUID campaignId);

    long countByCampaignIdAndStatus(UUID campaignId, ContactStatus status);

    /**
     * Count remaining dialable contacts — DialerEngine checks this to
     * decide if the campaign should be marked COMPLETED.
     */
    @Query("SELECT COUNT(c) FROM CampaignContact c WHERE c.campaign.id = :campaignId " +
            "AND c.status IN ('PENDING', 'NO_ANSWER', 'BUSY')")
    long countDialableContacts(UUID campaignId);

    // ── Bulk operations ──

    /**
     * Mark all PENDING contacts as DNC for a phone number across all campaigns
     * in a tenant. Called by ContactService when a number is added to DNC list.
     */
    @Modifying
    @Query("UPDATE CampaignContact c SET c.status = 'DNC', c.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE c.tenantId = :tenantId AND c.phoneNumber = :phoneNumber " +
            "AND c.status IN ('PENDING', 'NO_ANSWER', 'BUSY')")
    int markAsDnc(UUID tenantId, String phoneNumber);

    /**
     * Update contact status — called by DialerEngine after originate result.
     */
    @Modifying
    @Query("UPDATE CampaignContact c SET c.status = :status, c.attemptCount = c.attemptCount + 1, " +
            "c.lastAttemptAt = :attemptedAt, c.nextAttemptAt = :nextAttempt, " +
            "c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :contactId")
    int updateDialResult(UUID contactId, ContactStatus status, Instant attemptedAt, Instant nextAttempt);
}