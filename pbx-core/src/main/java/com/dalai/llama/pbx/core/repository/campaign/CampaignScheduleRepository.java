package com.dalai.llama.pbx.core.repository.campaign;

import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Campaign schedules — dialing time windows per day of week.
 *
 * Read paths:
 *   - DialerEngine → check if current time is within any schedule for a campaign
 *   - ScheduledTasks → campaign schedule executor scans all RUNNING/SCHEDULED campaigns
 *     every minute to auto-start (SCHEDULED→RUNNING) or auto-pause (RUNNING→PAUSED)
 *   - CampaignController → list schedules for a campaign
 *
 * Write paths:
 *   - CampaignController → set schedule when creating/updating campaign
 */
@Repository
public interface CampaignScheduleRepository extends JpaRepository<CampaignSchedule, UUID> {

    List<CampaignSchedule> findByCampaignId(UUID campaignId);

    /**
     * Is dialing allowed right now for this campaign?
     * Checks if current day + time falls within any schedule window.
     * Used by DialerEngine before originating calls.
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END " +
            "FROM CampaignSchedule s " +
            "WHERE s.campaign.id = :campaignId " +
            "AND s.dayOfWeek = :dayOfWeek " +
            "AND s.startTime <= :currentTime " +
            "AND s.endTime > :currentTime")
    boolean isWithinSchedule(UUID campaignId, int dayOfWeek, LocalTime currentTime);

    /**
     * All schedules for a specific day — used by ScheduledTasks to find
     * campaigns that should be started or paused at this moment.
     */
    @Query("SELECT s FROM CampaignSchedule s " +
            "WHERE s.dayOfWeek = :dayOfWeek " +
            "AND s.startTime <= :currentTime " +
            "AND s.endTime > :currentTime")
    List<CampaignSchedule> findActiveSchedulesForNow(int dayOfWeek, LocalTime currentTime);

    @Modifying
    void deleteByCampaignId(UUID campaignId);
}