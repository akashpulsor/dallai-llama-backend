package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    List<UsageRecord> findByTenantId(UUID tenantId);

    List<UsageRecord> findByTenantIdAndRecordedAtBetween(UUID tenantId, Instant from, Instant to);

    @Query("SELECT COALESCE(SUM(u.totalCost), 0) FROM UsageRecord u " +
            "WHERE u.tenantId = :tenantId AND u.recordedAt BETWEEN :from AND :to")
    BigDecimal sumCostByTenantIdAndPeriod(UUID tenantId, Instant from, Instant to);

    @Query("SELECT COALESCE(SUM(u.quantity), 0) FROM UsageRecord u " +
            "WHERE u.tenantId = :tenantId AND u.metric = :metric AND u.recordedAt BETWEEN :from AND :to")
    BigDecimal sumQuantityByTenantIdAndMetricAndPeriod(UUID tenantId, UsageMetric metric, Instant from, Instant to);

    long countByTenantIdAndRecordedAtBetween(UUID tenantId, Instant from, Instant to);
}
