package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.RecurringCharge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// RecurringChargeRepository
public interface RecurringChargeRepository extends JpaRepository<RecurringCharge, UUID> {

    List<RecurringCharge> findByTenantId(UUID tenantId);

    List<RecurringCharge> findBySubscriptionId(UUID subscriptionId);

    List<RecurringCharge> findByTenantIdAndStatus(UUID tenantId, String status);

    @Query("SELECT rc FROM RecurringCharge rc WHERE rc.nextChargeDate <= :date AND rc.status = 'ACTIVE'")
    List<RecurringCharge> findDueCharges(@Param("date") LocalDate date);

    List<RecurringCharge> findByTenantIdAndSourceTypeAndSourceId(UUID tenantId, String sourceType, UUID sourceId);

    @Modifying
    @Query("UPDATE RecurringCharge rc SET rc.status = 'CANCELLED', rc.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE rc.tenantId = :tenantId AND rc.sourceType = :sourceType AND rc.sourceId = :sourceId")
    void cancelBySource(@Param("tenantId") UUID tenantId,
                        @Param("sourceType") String sourceType,
                        @Param("sourceId") UUID sourceId);

    @Modifying
    @Query("UPDATE RecurringCharge rc SET rc.status = 'CANCELLED', rc.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE rc.subscriptionId = :subscriptionId")
    void cancelBySubscriptionId(@Param("subscriptionId") UUID subscriptionId);
}