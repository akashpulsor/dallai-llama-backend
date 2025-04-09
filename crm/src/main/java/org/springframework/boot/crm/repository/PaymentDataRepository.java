package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.PaymentData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PaymentDataRepository  extends JpaRepository<PaymentData, Integer> {


    Optional<PaymentData> findByCallId(int callId);
/*
    @Query("SELECT SUM(pd.totalToken) FROM PaymentData pd " +
            "JOIN CallLog cl ON pd.callId = cl.callLogId " +
            "JOIN campaign_run_data cr ON cl.campaignRunId = cr.campaignRunId " +
            "JOIN CampaignData cd ON cr.campaignId = cd.campaignId " +
            "WHERE cd.businessId = :businessId " +
            "AND cl.startTime BETWEEN :startDate AND :endDate")
    Integer sumTotalTokensWithJoins(
            @Param("businessId") Integer businessId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT SUM(pd.totalToken) FROM PaymentData pd " +
            "JOIN CallLog cl ON pd.callId = cl.callLogId " +
            "JOIN campaign_run_data cr ON cl.campaignRunId = cr.campaignRunId " +
            "JOIN CampaignData cd ON cr.campaignId = cd.campaignId " +
            "WHERE cd.businessId = :businessId " +
            "AND cl.startTime >= :startDate")
    Integer sumTotalTokensWithJoinsAfter(
            @Param("businessId") Integer businessId,
            @Param("startDate") LocalDateTime startDate
    );

    default Integer getTotalTokenUsageWithJoins(Integer businessId, LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate != null && endDate != null) {
            return sumTotalTokensWithJoins(businessId, startDate, endDate);
        } else {
            LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
            return sumTotalTokensWithJoinsAfter(businessId, sixtyDaysAgo);
        }
    }

    default Integer getTotalTokenUsageWithJoinsLast60Days(Integer businessId) {
        LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
        return sumTotalTokensWithJoinsAfter(businessId, sixtyDaysAgo);
    }

 */
}
