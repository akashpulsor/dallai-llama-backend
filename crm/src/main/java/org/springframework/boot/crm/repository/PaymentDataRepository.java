package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.dto.TokenAggregatesDto;
import org.springframework.boot.crm.entity.PaymentData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Date;
import java.util.Optional;

@EnableJpaRepositories
public interface PaymentDataRepository extends JpaRepository<PaymentData, Integer> {

    Optional<PaymentData> findByCallId(int callId);

    @Query("SELECT new org.springframework.boot.crm.dto.TokenAggregatesDto(" +
            "p.businessId as id, SUM(p.inputToken), SUM(p.outputToken), SUM(p.totalToken), SUM(p.callTime), " +
            "SUM(p.inputTextToken), SUM(p.inputAudioToken), SUM(p.inputCachedToken), " +
            "SUM(p.inputCachedTextToken), SUM(p.inputCachedAudioToken), " +
            "SUM(p.outputTextToken), SUM(p.outputAudioToken)) " +
            "FROM payment_data p WHERE p.businessId = :businessId GROUP BY p.businessId")
    TokenAggregatesDto findAggregatesByBusinessId(@Param("businessId") int businessId);

    @Query("SELECT new org.springframework.boot.crm.dto.TokenAggregatesDto(" +
            "p.businessId as id, SUM(p.inputToken), SUM(p.outputToken), SUM(p.totalToken), SUM(p.callTime), " +
            "SUM(p.inputTextToken), SUM(p.inputAudioToken), SUM(p.inputCachedToken), " +
            "SUM(p.inputCachedTextToken), SUM(p.inputCachedAudioToken), " +
            "SUM(p.outputTextToken), SUM(p.outputAudioToken)) " +
            "FROM payment_data p WHERE p.businessId = :businessId AND p.startTime >= :startTime AND p.endTime <= :endTime " +
            "GROUP BY p.businessId")
    TokenAggregatesDto findAggregatesByBusinessIdBetweenStartTimeAndEndTime(
            @Param("businessId") int businessId,
            @Param("startTime") LocalDate startTime,
            @Param("endTime") LocalDate endTime);

    @Query("SELECT new org.springframework.boot.crm.dto.TokenAggregatesDto(" +
            "p.campaignId as id, SUM(p.inputToken), SUM(p.outputToken), SUM(p.totalToken), SUM(p.callTime), " +
            "SUM(p.inputTextToken), SUM(p.inputAudioToken), SUM(p.inputCachedToken), " +
            "SUM(p.inputCachedTextToken), SUM(p.inputCachedAudioToken), " +
            "SUM(p.outputTextToken), SUM(p.outputAudioToken)) " +
            "FROM payment_data p WHERE p.campaignId = :campaignId GROUP BY p.campaignId")
    TokenAggregatesDto findAggregatesByCampaignId(@Param("campaignId") int campaignId);

    @Query("SELECT new org.springframework.boot.crm.dto.TokenAggregatesDto(" +
            "p.campaignRunId as id, SUM(p.inputToken), SUM(p.outputToken), SUM(p.totalToken), SUM(p.callTime), " +
            "SUM(p.inputTextToken), SUM(p.inputAudioToken), SUM(p.inputCachedToken), " +
            "SUM(p.inputCachedTextToken), SUM(p.inputCachedAudioToken), " +
            "SUM(p.outputTextToken), SUM(p.outputAudioToken)) " +
            "FROM payment_data p WHERE p.campaignRunId = :campaignRunId GROUP BY p.campaignRunId")
    TokenAggregatesDto findAggregatesByCampaignRunId(@Param("campaignRunId") int campaignRunId);
}
