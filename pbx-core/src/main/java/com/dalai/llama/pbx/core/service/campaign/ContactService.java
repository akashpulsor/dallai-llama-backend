package com.dalai.llama.pbx.core.service.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignContact;
import com.dalai.llama.pbx.core.domain.entity.campaign.DncEntry;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import com.dalai.llama.pbx.core.domain.enums.DncSource;
import com.dalai.llama.pbx.core.repository.campaign.CampaignContactRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import com.dalai.llama.pbx.core.repository.campaign.DncEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Contact and DNC list management.
 *
 * Contacts are the outbound dial list for campaigns.
 * DNC (Do-Not-Call) entries are tenant-scoped and checked on every outbound call.
 *
 * When a number is added to DNC, all PENDING contacts with that number
 * across all campaigns for the tenant are automatically marked as DNC.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private final CampaignContactRepository contactRepository;
    private final CampaignRepository campaignRepository;
    private final DncEntryRepository dncRepository;

    // ═══════════════════════════════════════════════════════════
    // CONTACT IMPORT
    // ═══════════════════════════════════════════════════════════

    /**
     * Bulk import contacts for a campaign.
     * Skips duplicates (same phone number in same campaign) and DNC numbers.
     * Returns count of successfully imported contacts.
     */
    @Transactional
    public int importContacts(UUID campaignId, UUID tenantId, List<Map<String, Object>> contactRows) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

        int imported = 0;
        int skippedDuplicate = 0;
        int skippedDnc = 0;

        for (Map<String, Object> row : contactRows) {
            String phoneNumber = String.valueOf(row.get("phone_number"));
            if (phoneNumber == null || phoneNumber.isBlank()) continue;

            // Skip duplicate
            if (contactRepository.existsByCampaignIdAndPhoneNumber(campaignId, phoneNumber)) {
                skippedDuplicate++;
                continue;
            }

            // Skip DNC
            if (dncRepository.isOnDncList(tenantId, phoneNumber, Instant.now())) {
                skippedDnc++;
                continue;
            }

            CampaignContact contact = CampaignContact.builder()
                    .campaign(campaign)
                    .tenantId(tenantId)
                    .phoneNumber(phoneNumber)
                    .name(safeString(row, "name"))
                    .email(safeString(row, "email"))
                    .company(safeString(row, "company"))
                    .priority(safeInt(row, "priority", 0))
                    .build();

            // Custom fields go into custom_data JSONB
            Map<String, Object> customData = new HashMap<>(row);
            customData.remove("phone_number");
            customData.remove("name");
            customData.remove("email");
            customData.remove("company");
            customData.remove("priority");
            if (!customData.isEmpty()) {
                contact.setCustomData(customData);
            }

            contactRepository.save(contact);
            imported++;
        }

        // Update campaign total_contacts
        long total = contactRepository.countByCampaignId(campaignId);
        campaign.setTotalContacts((int) total);
        campaignRepository.save(campaign);

        log.info("Imported {} contacts for campaign {} (skipped: {} duplicate, {} DNC)",
                imported, campaignId, skippedDuplicate, skippedDnc);
        return imported;
    }

    // ═══════════════════════════════════════════════════════════
    // CONTACT QUERIES
    // ═══════════════════════════════════════════════════════════

    public Page<CampaignContact> getContacts(UUID campaignId, Pageable pageable) {
        return contactRepository.findByCampaignId(campaignId, pageable);
    }

    public Page<CampaignContact> getContactsByStatus(UUID campaignId, ContactStatus status, Pageable pageable) {
        return contactRepository.findByCampaignIdAndStatus(campaignId, status, pageable);
    }

    public Map<String, Long> getContactStats(UUID campaignId) {
        Map<String, Long> stats = new LinkedHashMap<>();
        for (ContactStatus status : ContactStatus.values()) {
            stats.put(status.name(), contactRepository.countByCampaignIdAndStatus(campaignId, status));
        }
        stats.put("TOTAL", contactRepository.countByCampaignId(campaignId));
        stats.put("DIALABLE", contactRepository.countDialableContacts(campaignId));
        return stats;
    }

    // ═══════════════════════════════════════════════════════════
    // DNC MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    /**
     * Add a number to DNC list.
     * Also marks all PENDING contacts with this number as DNC across all campaigns.
     */
    @Transactional
    public DncEntry addToDnc(UUID tenantId, String phoneNumber, String reason, DncSource source, Instant expiresAt) {
        // Check if already on DNC
        if (dncRepository.existsByTenantIdAndPhoneNumber(tenantId, phoneNumber)) {
            log.debug("Phone {} already on DNC for tenant {}", phoneNumber, tenantId);
            return dncRepository.findByTenantIdAndPhoneNumber(tenantId, phoneNumber).orElseThrow();
        }

        DncEntry entry = DncEntry.builder()
                .tenantId(tenantId)
                .phoneNumber(phoneNumber)
                .reason(reason)
                .source(source)
                .expiresAt(expiresAt)
                .build();
        entry = dncRepository.save(entry);

        // Mark all pending contacts with this number as DNC
        int marked = contactRepository.markAsDnc(tenantId, phoneNumber);
        if (marked > 0) {
            log.info("DNC added for {} — marked {} pending contacts", phoneNumber, marked);
        }

        return entry;
    }

    @Transactional
    public void removeFromDnc(UUID tenantId, String phoneNumber) {
        dncRepository.deleteByTenantIdAndPhoneNumber(tenantId, phoneNumber);
        log.info("Removed {} from DNC for tenant {}", phoneNumber, tenantId);
    }

    public boolean isOnDnc(UUID tenantId, String phoneNumber) {
        return dncRepository.isOnDncList(tenantId, phoneNumber, Instant.now());
    }

    public Page<DncEntry> getDncList(UUID tenantId, Pageable pageable) {
        return dncRepository.findByTenantId(tenantId, pageable);
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════

    private String safeString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private int safeInt(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }
}