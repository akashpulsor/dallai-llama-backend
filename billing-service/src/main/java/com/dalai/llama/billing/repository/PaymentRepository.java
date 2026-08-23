package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// PaymentRepository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByGatewayOrderId(String gatewayOrderId);

    Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId);

    List<Payment> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** Backs the per-project funding view -- every payment ever created against one brief,
     * regardless of status, newest first. */
    List<Payment> findByProjectRequirementIdOrderByCreatedAtDesc(UUID projectRequirementId);

    /** Used by PaymentFailureChecker to expire stale PENDING payments */
    List<Payment> findByStatusAndExpiredAtBefore(PaymentStatus status, Instant time);
}