package com.dalai.llama.pbx.core.service.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignSchedule;
import com.dalai.llama.pbx.core.domain.enums.CampaignStatus;
import com.dalai.llama.pbx.core.domain.enums.CampaignType;
import com.dalai.llama.pbx.core.repository.campaign.CampaignContactRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Campaign lifecycle management.
 *
 * State machine:
 *   DRAFT → SCHEDULED → RUNNING → PAUSED → RUNNING → COMPLETED
 *                 ↓                   ↓
 *              CANCELLED          CANCELLED
 *
 * DialerEngine picks RUNNING outbound campaigns every 5 seconds.
 * ScheduledTasks manages auto-start/pause based on campaign_schedules.
 *
 * Inbound campaigns are matched by DID number in CallAuthorizationService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final CampaignScheduleRepository scheduleRepository;
    private final CampaignContactRepository contactRepository;

    // ═══════════════════════════════════════════════════════════
    // CRUD
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Campaign createCampaign(Campaign campaign) {
        if (campaignRepository.existsByTenantIdAndName(campaign.getTenantId(), campaign.getName())) {
            throw new IllegalArgumentException("Campaign '" + campaign.getName() + "' already exists");
        }
        campaign.setStatus(CampaignStatus.DRAFT);
        campaign = campaignRepository.save(campaign);
        log.info("Created campaign '{}' ({}) for tenant {}",
                campaign.getName(), campaign.getCampaignType(), campaign.getTenantId());
        return campaign;
    }

    @Transactional
    public Campaign updateCampaign(UUID campaignId, Campaign updates) {
        Campaign campaign = getOrThrow(campaignId);

        if (updates.getName() != null) campaign.setName(updates.getName());
        if (updates.getDescription() != null) campaign.setDescription(updates.getDescription());
        if (updates.getDialerMode() != null) campaign.setDialerMode(updates.getDialerMode());
        if (updates.getPacingRatio() != null) campaign.setPacingRatio(updates.getPacingRatio());
        if (updates.getMaxConcurrentCalls() != null) campaign.setMaxConcurrentCalls(updates.getMaxConcurrentCalls());
        if (updates.getMaxAttemptsPerContact() != null) campaign.setMaxAttemptsPerContact(updates.getMaxAttemptsPerContact());
        if (updates.getRetryDelayMinutes() != null) campaign.setRetryDelayMinutes(updates.getRetryDelayMinutes());
        if (updates.getMaxDailyCalls() != null) campaign.setMaxDailyCalls(updates.getMaxDailyCalls());
        if (updates.getMaxTotalCalls() != null) campaign.setMaxTotalCalls(updates.getMaxTotalCalls());
        if (updates.getTimezone() != null) campaign.setTimezone(updates.getTimezone());
        if (updates.getStartDate() != null) campaign.setStartDate(updates.getStartDate());
        if (updates.getEndDate() != null) campaign.setEndDate(updates.getEndDate());

        return campaignRepository.save(campaign);
    }

    // ═══════════════════════════════════════════════════════════
    // LIFECYCLE STATE MACHINE
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Campaign start(UUID campaignId) {
        Campaign campaign = getOrThrow(campaignId);
        validateTransition(campaign.getStatus(), CampaignStatus.RUNNING);

        campaign.setStatus(CampaignStatus.RUNNING);
        campaign.setStartedAt(Instant.now());

        // Update total contacts count
        long total = contactRepository.countByCampaignId(campaignId);
        campaign.setTotalContacts((int) total);

        campaign = campaignRepository.save(campaign);
        log.info("Campaign '{}' STARTED (tenant={}, contacts={})",
                campaign.getName(), campaign.getTenantId(), total);
        return campaign;
    }

    @Transactional
    public Campaign pause(UUID campaignId) {
        Campaign campaign = getOrThrow(campaignId);
        validateTransition(campaign.getStatus(), CampaignStatus.PAUSED);

        campaign.setStatus(CampaignStatus.PAUSED);
        campaign = campaignRepository.save(campaign);
        log.info("Campaign '{}' PAUSED", campaign.getName());
        return campaign;
    }

    @Transactional
    public Campaign resume(UUID campaignId) {
        Campaign campaign = getOrThrow(campaignId);
        validateTransition(campaign.getStatus(), CampaignStatus.RUNNING);

        campaign.setStatus(CampaignStatus.RUNNING);
        campaign = campaignRepository.save(campaign);
        log.info("Campaign '{}' RESUMED", campaign.getName());
        return campaign;
    }

    @Transactional
    public Campaign cancel(UUID campaignId) {
        Campaign campaign = getOrThrow(campaignId);

        campaign.setStatus(CampaignStatus.CANCELLED);
        campaign.setCompletedAt(Instant.now());
        campaign = campaignRepository.save(campaign);
        log.info("Campaign '{}' CANCELLED", campaign.getName());
        return campaign;
    }

    @Transactional
    public Campaign complete(UUID campaignId) {
        Campaign campaign = getOrThrow(campaignId);

        campaign.setStatus(CampaignStatus.COMPLETED);
        campaign.setCompletedAt(Instant.now());
        campaign = campaignRepository.save(campaign);
        log.info("Campaign '{}' COMPLETED", campaign.getName());
        return campaign;
    }

    // ═══════════════════════════════════════════════════════════
    // SCHEDULES
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void setSchedules(UUID campaignId, List<CampaignSchedule> schedules) {
        Campaign campaign = getOrThrow(campaignId);
        scheduleRepository.deleteByCampaignId(campaignId);

        for (CampaignSchedule schedule : schedules) {
            schedule.setCampaign(campaign);
        }
        scheduleRepository.saveAll(schedules);
        log.info("Set {} schedules for campaign {}", schedules.size(), campaignId);
    }

    public List<CampaignSchedule> getSchedules(UUID campaignId) {
        return scheduleRepository.findByCampaignId(campaignId);
    }

    public boolean isWithinSchedule(UUID campaignId, int dayOfWeek, LocalTime currentTime) {
        return scheduleRepository.isWithinSchedule(campaignId, dayOfWeek, currentTime);
    }

    // ═══════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════

    public List<Campaign> getByTenantId(UUID tenantId) {
        return campaignRepository.findByTenantId(tenantId);
    }

    public List<Campaign> getRunningCampaigns() {
        return campaignRepository.findByStatus(CampaignStatus.RUNNING);
    }

    public List<Campaign> getRunningOutbound(UUID tenantId) {
        return campaignRepository.findByTenantIdAndCampaignTypeAndStatus(
                tenantId, CampaignType.OUTBOUND, CampaignStatus.RUNNING);
    }

    public Optional<Campaign> getById(UUID campaignId) {
        return campaignRepository.findById(campaignId);
    }

    public Optional<Campaign> getByDid(String didNumber) {
        return campaignRepository.findByDidNumberAndStatus(didNumber, CampaignStatus.RUNNING);
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════

    private Campaign getOrThrow(UUID id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + id));
    }

    private void validateTransition(CampaignStatus current, CampaignStatus target) {
        boolean valid = switch (target) {
            case RUNNING -> current == CampaignStatus.DRAFT
                    || current == CampaignStatus.SCHEDULED
                    || current == CampaignStatus.PAUSED;
            case PAUSED -> current == CampaignStatus.RUNNING;
            case COMPLETED -> current == CampaignStatus.RUNNING || current == CampaignStatus.PAUSED;
            case CANCELLED -> current != CampaignStatus.COMPLETED && current != CampaignStatus.CANCELLED;
            default -> false;
        };
        if (!valid) {
            throw new IllegalStateException("Invalid transition: " + current + " → " + target);
        }
    }
}