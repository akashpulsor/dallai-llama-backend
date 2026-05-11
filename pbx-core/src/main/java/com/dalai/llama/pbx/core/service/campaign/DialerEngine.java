package com.dalai.llama.pbx.core.service.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignContact;
import com.dalai.llama.pbx.core.domain.enums.CampaignStatus;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import com.dalai.llama.pbx.core.repository.campaign.CampaignContactRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignScheduleRepository;
import com.dalai.llama.pbx.core.repository.campaign.DncEntryRepository;
import com.dalai.llama.pbx.core.service.call.CallControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound campaign dialer engine.
 *
 * Runs every N seconds (configurable). For each RUNNING outbound campaign:
 *   1. Check if within schedule window (campaign_schedules)
 *   2. Check concurrency limit (max_concurrent_calls vs active calls)
 *   3. Pick next dialable contact (findNextDialable — priority DESC, FIFO)
 *   4. DNC recheck (in case added since import)
 *   5. Originate call via ESL (CallControlService)
 *   6. Update contact status (PENDING → DIALING)
 *   7. On result (from EslEventListener): update disposition, schedule retry or complete
 *
 * Auto-completes campaigns when no dialable contacts remain.
 *
 * Dialer modes:
 *   PROGRESSIVE — dial one contact per available agent (1:1 ratio)
 *   PREDICTIVE  — overdial based on pacing_ratio (e.g., 1.2 = 20% more calls than agents)
 *   PREVIEW     — assigned to specific agent, agent clicks to dial
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialerEngine {

    private final CampaignRepository campaignRepository;
    private final CampaignContactRepository contactRepository;
    private final CampaignScheduleRepository scheduleRepository;
    private final DncEntryRepository dncRepository;
    private final CallControlService callControlService;
    private final ActiveCallTracker callTracker;

    @Value("${pbxcore.dialer.max-concurrent-per-campaign:10}")
    private int globalMaxConcurrent;

    // ═══════════════════════════════════════════════════════════
    // SCHEDULED POLL — runs every 5 seconds
    // ═══════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "${pbxcore.dialer.poll-interval-ms:5000}")
    public void poll() {
        List<Campaign> runningCampaigns = campaignRepository.findByStatus(CampaignStatus.RUNNING);
        if (runningCampaigns.isEmpty()) return;

        for (Campaign campaign : runningCampaigns) {
            try {
                processCampaign(campaign);
            } catch (Exception e) {
                log.error("Dialer error for campaign {}: {}", campaign.getId(), e.getMessage(), e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PROCESS ONE CAMPAIGN
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void processCampaign(Campaign campaign) {
        UUID campaignId = campaign.getId();
        UUID tenantId = campaign.getTenantId();

        // 1. Schedule check
        ZoneId tz = ZoneId.of(campaign.getTimezone() != null ? campaign.getTimezone() : "Asia/Kolkata");
        LocalDateTime now = LocalDateTime.now(tz);
        int dayOfWeek = now.getDayOfWeek().getValue();
        LocalTime currentTime = now.toLocalTime();

        // If campaign has schedules, check if we're in window
        List<?> schedules = scheduleRepository.findByCampaignId(campaignId);
        if (!schedules.isEmpty() && !scheduleRepository.isWithinSchedule(campaignId, dayOfWeek, currentTime)) {
            return; // Outside schedule window — skip
        }

        // 2. Check remaining contacts
        long dialable = contactRepository.countDialableContacts(campaignId);
        if (dialable == 0) {
            // Auto-complete campaign
            campaign.setStatus(CampaignStatus.COMPLETED);
            campaign.setCompletedAt(Instant.now());
            campaignRepository.save(campaign);
            log.info("Campaign '{}' auto-completed — no dialable contacts", campaign.getName());
            return;
        }

        // 3. Concurrency check
        int maxConcurrent = Math.min(
                campaign.getMaxConcurrentCalls() != null ? campaign.getMaxConcurrentCalls() : globalMaxConcurrent,
                globalMaxConcurrent
        );
        long activeCampaignCalls = callTracker.getActiveCallCount(tenantId); // Simplified — ideally filter by campaignId
        if (activeCampaignCalls >= maxConcurrent) {
            return; // At capacity
        }

        // 4. How many to dial this tick
        int slotsAvailable = (int) (maxConcurrent - activeCampaignCalls);
        int toDial = Math.max(1, slotsAvailable);

        // 5. Pick contacts and dial
        Page<CampaignContact> contacts = contactRepository.findNextDialable(
                campaignId, Instant.now(), PageRequest.of(0, toDial));

        for (CampaignContact contact : contacts) {
            dialContact(campaign, contact);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DIAL ONE CONTACT
    // ═══════════════════════════════════════════════════════════

    private void dialContact(Campaign campaign, CampaignContact contact) {
        UUID tenantId = campaign.getTenantId();
        String phoneNumber = contact.getPhoneNumber();

        // DNC recheck (number may have been added since import)
        if (dncRepository.isOnDncList(tenantId, phoneNumber, Instant.now())) {
            contactRepository.updateDialResult(contact.getId(), ContactStatus.DNC, Instant.now(), null);
            log.debug("Contact {} skipped — DNC", phoneNumber);
            return;
        }

        // Max attempts check
        if (contact.getAttemptCount() >= campaign.getMaxAttemptsPerContact()) {
            contactRepository.updateDialResult(contact.getId(), ContactStatus.FAILED, Instant.now(), null);
            log.debug("Contact {} max attempts reached ({})", phoneNumber, contact.getAttemptCount());
            return;
        }

        // Mark as DIALING
        contactRepository.updateDialResult(contact.getId(), ContactStatus.DIALING, Instant.now(), null);

        // Build origination context
        String callerId = campaign.getOutboundCallerId() != null
                ? campaign.getOutboundCallerId()
                : campaign.getDidNumber();

        String context = "tenant_" + campaign.getTenantId().toString().replace("-", "");

        Map<String, String> variables = Map.of(
                "campaign_id", campaign.getId().toString(),
                "contact_id", contact.getId().toString(),
                "product_code", "OUTBOUND_DIALER",
                "bot_id", campaign.getBot() != null ? campaign.getBot().getId().toString() : ""
        );

        try {
            String jobUuid = callControlService.originate(
                    callerId != null ? callerId : "anonymous",
                    phoneNumber,
                    tenantId,
                    context,
                    variables
            );

            campaignRepository.incrementContactsDialed(campaign.getId());
            log.info("Dialed contact {} for campaign '{}' (jobUuid={})",
                    phoneNumber, campaign.getName(), jobUuid);

        } catch (Exception e) {
            // Origination failed — schedule retry
            Instant nextAttempt = Instant.now().plus(
                    campaign.getRetryDelayMinutes() != null ? campaign.getRetryDelayMinutes() : 60,
                    ChronoUnit.MINUTES
            );
            contactRepository.updateDialResult(contact.getId(), ContactStatus.FAILED, Instant.now(), nextAttempt);
            log.warn("Failed to dial {} for campaign '{}': {}", phoneNumber, campaign.getName(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CALL RESULT CALLBACK (called by EslEventListener or CdrService)
    // ═══════════════════════════════════════════════════════════

    /**
     * Update contact disposition after call completes.
     * Called when CDR for a campaign call is finalized.
     */
    @Transactional
    public void handleCallResult(UUID contactId, ContactStatus finalStatus,
                                 String disposition, Integer durationSeconds, String callId) {
        CampaignContact contact = contactRepository.findById(contactId).orElse(null);
        if (contact == null) return;

        contact.setDisposition(disposition);
        contact.setDurationSeconds(durationSeconds);
        contact.setCallId(callId);

        // Auto-qualify based on disposition from bot intent detection
        // ai-service sets disposition to "interested" / "not_interested" via escalation callback
        ContactStatus resolvedStatus = finalStatus;
        if (disposition != null) {
            String d = disposition.toLowerCase();
            if (d.contains("interested") && !d.contains("not_interested")) {
                resolvedStatus = ContactStatus.QUALIFIED;
            } else if (d.contains("not_interested")) {
                resolvedStatus = ContactStatus.NOT_QUALIFIED;
            }
        }
        contact.setStatus(resolvedStatus);

        if (resolvedStatus == ContactStatus.COMPLETED || resolvedStatus == ContactStatus.QUALIFIED
                || resolvedStatus == ContactStatus.NOT_QUALIFIED) {
            contact.setCompletedAt(Instant.now());
            campaignRepository.incrementContactsCompleted(contact.getCampaign().getId());
        } else if (resolvedStatus == ContactStatus.CONNECTED) {
            campaignRepository.incrementContactsConnected(contact.getCampaign().getId());
        } else if (resolvedStatus == ContactStatus.NO_ANSWER || resolvedStatus == ContactStatus.BUSY) {
            // Schedule retry
            Campaign campaign = contact.getCampaign();
            Instant nextAttempt = Instant.now().plus(
                    campaign.getRetryDelayMinutes() != null ? campaign.getRetryDelayMinutes() : 60,
                    ChronoUnit.MINUTES
            );
            contact.setNextAttemptAt(nextAttempt);
        }

        contactRepository.save(contact);
    }
}