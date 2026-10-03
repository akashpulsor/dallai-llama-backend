package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientReviewPaymentRepository extends JpaRepository<ClientReviewPayment, UUID> {

    Optional<ClientReviewPayment> findByGatewayOrderId(String gatewayOrderId);

    List<ClientReviewPayment> findByTenantIdAndProjectIdAndStatus(UUID tenantId, UUID projectId, String status);

    @Query("SELECT DISTINCT p.projectId FROM ClientReviewPayment p WHERE p.tenantId = :tenantId AND p.status = 'SUCCESS'")
    List<UUID> findPaidProjectIdsByTenantId(@Param("tenantId") UUID tenantId);
}
