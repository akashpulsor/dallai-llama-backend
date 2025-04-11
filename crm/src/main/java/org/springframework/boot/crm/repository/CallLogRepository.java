package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CallLogRepository  extends JpaRepository<CallLog, Integer> {

    Optional<CallLog> findByCallTypeAndCampaignRunIdAndLeadId(String callType, int campaignRunId, int leadId);

    @Query("SELECT cl FROM call_log cl WHERE cl.campaignRunId = :campaignRunId ORDER BY cl.callLogId DESC, cl.startTime DESC")
    Page<CallLog> findByCampaignRunIdOrderByCallLogIdAndStartTime(
            @Param("campaignRunId") int campaignRunId,
            Pageable pageable
    );


    @Query("SELECT cl FROM call_log cl WHERE cl.campaignRunId = :campaignRunId ORDER BY cl.callLogId DESC, cl.startTime DESC")
    List<CallLog> findByCampaignRunIdOrderByCallLogIdAndStartTime(
            @Param("campaignRunId") int campaignRunId
    );

/*
    @Query("SELECT COUNT(cl) FROM CallLog cl " +
            "JOIN campaign_run_data cr ON cl.campaignRunId = cr.campaignRunId " +
            "JOIN CampaignData cd ON cr.campaignId = cd.campaignId " +
            "WHERE cd.businessId = :businessId " +
            "AND cl.startTime BETWEEN :startDate AND :endDate")
    long countTotalCallsWithJoins(
            @Param("businessId") Integer businessId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("SELECT COUNT(cl) FROM CallLog cl " +
            "JOIN campaign_run_data cr ON cl.campaignRunId = cr.campaignRunId " +
            "JOIN CampaignData cd ON cr.campaignId = cd.campaignId " +
            "WHERE cd.businessId = :businessId " +
            "AND cl.startTime >= :startDate")
    long countTotalCallsWithJoinsAfter(
            @Param("businessId") Integer businessId,
            @Param("startDate") LocalDate startDate
    );

    default long countTotalCalls(Integer businessId, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return countTotalCallsWithJoins(businessId, startDate, endDate);
        } else {
            LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
            return countTotalCallsWithJoinsAfter(businessId, LocalDate.from(sixtyDaysAgo));
        }
    }

    default long countTotalCallsLast60Days(Integer businessId) {
        LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
        return countTotalCallsWithJoinsAfter(businessId, LocalDate.from(sixtyDaysAgo));
    }*/
    
}
