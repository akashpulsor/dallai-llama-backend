package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByGatewayOrderId(String gatewayOrderId);

    List<Payment> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
