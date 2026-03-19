package com.dalai.llama.pbx.core.repository.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.domain.enums.CampaignStatus;
import com.dalai.llama.pbx.core.domain.enums.CampaignType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Campaign table — inbound and outbound campaigns.
 *
 * Read paths:
 *   - DialerEngine (@Scheduled) → findByStatus(RUNNING) to pick active outbound campaigns
 *   - CallAuthorizationService → findByDidNumber for inbound campaign routing
 *   - CampaignController → CRUD listing + stats
 *   - ScheduledTasks → campaign schedule executor (auto-start/pause based on time windows)
 *
 * Write paths:
 *   - CampaignController CRUD + lifecycle transitions (DRAFT→RUNNING→COMPLETED)
 *   - DialerEngine → updates denormalized stats (contacts_dialed, contacts_connected)
 */
@Repository
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    // ── Tenant-scoped listing ──

    List<Campaign> findByTenantId(UUID tenantId);

    List<Campaign> findByTenantIdAndCampaignType(UUID tenantId, CampaignType type);

    List<Campaign> findByTenantIdAndStatus(UUID tenantId, CampaignStatus status);

    Optional<Campaign> findByTenantIdAndName(UUID tenantId, String name);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    // ── DID-based lookup (inbound campaigns) ──

    /**
     * Inbound campaign routing — when a call hits a DID assigned to a campaign,
     * CallAuthorizationService resolves the campaign and its bot.
     */
    Optional<Campaign> findByDidNumberAndStatus(String didNumber, CampaignStatus status);

    Optional<Campaign> findByDidNumber(String didNumber);

    // ── DialerEngine queries ──

    /**
     * All RUNNING campaigns — DialerEngine polls this every 5 seconds
     * to find campaigns that need contacts dialed.
     */
    List<Campaign> findByStatus(CampaignStatus status);

    /**
     * RUNNING outbound campaigns for a specific tenant.
     */
    List<Campaign> findByTenantIdAndCampaignTypeAndStatus(
            UUID tenantId, CampaignType type, CampaignStatus status);

    // ── Stats update (DialerEngine) ──

    /**
     * Increment contacts_dialed counter atomically.
     * Called by DialerEngine after each originate.
     */
    @Modifying
    @Query("UPDATE Campaign c SET c.contactsDialed = c.contactsDialed + 1, " +
            "c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :campaignId")
    int incrementContactsDialed(UUID campaignId);

    @Modifying
    @Query("UPDATE Campaign c SET c.contactsConnected = c.contactsConnected + 1, " +
            "c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :campaignId")
    int incrementContactsConnected(UUID campaignId);

    @Modifying
    @Query("UPDATE Campaign c SET c.contactsCompleted = c.contactsCompleted + 1, " +
            "c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :campaignId")
    int incrementContactsCompleted(UUID campaignId);

    // ── Counts ──

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, CampaignStatus status);
}