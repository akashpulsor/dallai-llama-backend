package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.dto.ChargesSummaryDto;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.ChargesData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

public interface ChargesDataRepository extends JpaRepository<ChargesData, Integer> {

    // 1. Get Charges by Call ID
    @Query("SELECT c FROM charges_data c WHERE c.callId = :callId")
    ChargesData findByCallId(@Param("callId") int callId);

    @Query("SELECT new org.springframework.boot.crm.dto.ChargesSummaryDto(" +
            "SUM(c.totalCharges), SUM(c.serviceCharges), SUM(c.modelCharges), " +
            "SUM(c.carrierCharges), SUM(c.effectiveCost), c.campaignId) " +
            "FROM charges_data c WHERE c.campaignId = :campaignId GROUP BY c.campaignId")
    ChargesSummaryDto findChargesSummaryByCampaignId(@Param("campaignId") int campaignId);

    @Query("SELECT new org.springframework.boot.crm.dto.ChargesSummaryDto(" +
            "SUM(c.totalCharges), SUM(c.serviceCharges), SUM(c.modelCharges), " +
            "SUM(c.carrierCharges), SUM(c.effectiveCost), c.campaignRunId) " +
            "FROM charges_data c WHERE c.campaignRunId = :campaignRunId GROUP BY c.campaignRunId")
    ChargesSummaryDto findChargesSummaryByCampaignRunId(@Param("campaignRunId") int campaignRunId);

    @Query("SELECT new org.springframework.boot.crm.dto.ChargesSummaryDto(" +
            "SUM(c.totalCharges), SUM(c.serviceCharges), SUM(c.modelCharges), " +
            "SUM(c.carrierCharges), SUM(c.effectiveCost), c.businessId) " +
            "FROM charges_data c WHERE c.businessId = :businessId GROUP BY c.businessId")
    ChargesSummaryDto findChargesSummaryByBusinessId(@Param("businessId") int businessId);

    @Query("SELECT new org.springframework.boot.crm.dto.ChargesSummaryDto(" +
            "SUM(c.totalCharges), SUM(c.serviceCharges), SUM(c.modelCharges), " +
            "SUM(c.carrierCharges), SUM(c.effectiveCost), c.businessId) " +
            "FROM charges_data c WHERE c.businessId = :businessId " +
            "AND c.callStartTime >= :startTime AND c.callEndTime <= :endTime GROUP BY c.businessId")
    ChargesSummaryDto findChargesSummaryByBusinessIdBetweenStartTimeAndEndTime(
            @Param("businessId") int businessId,
            @Param("startTime") LocalDate startTime,
            @Param("endTime") LocalDate endTime);
}