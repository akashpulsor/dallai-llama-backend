package com.dalai.llama.pbx.core.service.scheduled;


import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignSchedule;
import com.dalai.llama.pbx.core.domain.enums.CampaignStatus;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignScheduleRepository;
import com.dalai.llama.pbx.core.repository.campaign.DncEntryRepository;
import com.dalai.llama.pbx.core.repository.cdr.CallRecordRepository;
import com.dalai.llama.pbx.core.repository.core.TenantConfigCacheRepository;
import com.dalai.llama.pbx.core.repository.core.TurnCredentialsCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Background scheduled tasks — housekeeping and automation.
 *
 * All tasks are idempotent and safe to run on multiple PBX-Core replicas
 * (they operate on DB with appropriate WHERE clauses, not in-memory state).
 *
 * Tasks:
 *   1. Expired TURN credential cleanup (hourly)
 *   2. Stale tenant_config_cache eviction (every 5 minutes)
 *   3. Agent auto-logout — NOT scheduled here; handled by EslEventListener
 *      when SIP REGISTER expires (FreeSWITCH fires CHANNEL_HANGUP or
 *      registration timeout event). Future: heartbeat-based logout.
 *   4. Campaign schedule executor (every 60 seconds)
 *   5. Stale CDR closure (every 5 minutes)
 *   6. DNC expiry cleanup (hourly)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTasks {

    private final TurnCredentialsCacheRepository turnCacheRepository;
    private final TenantConfigCacheRepository configCacheRepository;
    private final CallRecordRepository callRecordRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignScheduleRepository scheduleRepository;
    private final DncEntryRepository dncRepository;

    // ═══════════════════════════════════════════════════════════
    // 1. TURN CREDENTIAL CLEANUP — every hour
    // ═══════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 3600_000) // 1 hour
    @Transactional
    public void cleanupExpiredTurnCredentials() {
        Instant now = Instant.now();
        turnCacheRepository.deleteByExpiresAtBefore(now);
        log.debug("Cleaned expired TURN credentials (before {})", now);
    }

    // ═══════════════════════════════════════════════════════════
    // 2. TENANT CONFIG CACHE EVICTION — every 5 minutes
    // ═══════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 300_000) // 5 minutes
    @Transactional
    public void evictExpiredConfigCache() {
        Instant now = Instant.now();
        configCacheRepository.deleteByExpiresAtBefore(now);
        log.debug("Evicted expired tenant config cache entries (before {})", now);
    }

    // ═══════════════════════════════════════════════════════════
    // 3. CAMPAIGN SCHEDULE EXECUTOR — every 60 seconds
    //    Auto-starts SCHEDULED campaigns when schedule window opens.
    //    Auto-pauses RUNNING campaigns when schedule window closes.
    // ═══════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60_000) // 1 minute
    @Transactional
    public void executeCampaignSchedules() {
        int dayOfWeek = java.time.LocalDate.now().getDayOfWeek().getValue();
        LocalTime now = LocalTime.now();

        // Find campaigns that should be RUNNING right now
        List<CampaignSchedule> activeSchedules = scheduleRepository.findActiveSchedulesForNow(dayOfWeek, now);

        for (CampaignSchedule schedule : activeSchedules) {
            Campaign campaign = schedule.getCampaign();
            if (campaign.getStatus() == CampaignStatus.SCHEDULED
                    || campaign.getStatus() == CampaignStatus.PAUSED) {
                campaign.setStatus(CampaignStatus.RUNNING);
                if (campaign.getStartedAt() == null) {
                    campaign.setStartedAt(Instant.now());
                }
                campaignRepository.save(campaign);
                log.info("Auto-started campaign '{}' (schedule window opened)", campaign.getName());
            }
        }

        // Find RUNNING campaigns that are OUTSIDE their schedule window
        List<Campaign> runningCampaigns = campaignRepository.findByStatus(CampaignStatus.RUNNING);
        for (Campaign campaign : runningCampaigns) {
            List<CampaignSchedule> schedules = scheduleRepository.findByCampaignId(campaign.getId());
            if (!schedules.isEmpty()) {
                boolean withinAnySchedule = schedules.stream()
                        .anyMatch(s -> s.getDayOfWeek() == dayOfWeek
                                && !now.isBefore(s.getStartTime())
                                && now.isBefore(s.getEndTime()));

                if (!withinAnySchedule) {
                    campaign.setStatus(CampaignStatus.PAUSED);
                    campaignRepository.save(campaign);
                    log.info("Auto-paused campaign '{}' (schedule window closed)", campaign.getName());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 4. STALE CDR CLOSURE — every 5 minutes
    //    Calls stuck in RINGING for >10 minutes are marked MISSED.
    //    Safety net for when Kamailio call-end event is lost.
    // ═══════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 300_000) // 5 minutes
    @Transactional
    public void closeStaleCdrs() {
        Instant cutoff = Instant.now().minus(10, ChronoUnit.MINUTES);
        int closed = callRecordRepository.closeStaleRingingCalls(cutoff);
        if (closed > 0) {
            log.info("Closed {} stale RINGING CDRs (started before {})", closed, cutoff);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 5. DNC EXPIRY CLEANUP — every hour
    //    Removes DNC entries that have passed their expires_at.
    //    Permanent entries (expires_at IS NULL) are not affected.
    // ═══════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 3600_000) // 1 hour
    @Transactional
    public void cleanupExpiredDncEntries() {
        int deleted = dncRepository.deleteExpiredEntries(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned {} expired DNC entries", deleted);
        }
    }
}