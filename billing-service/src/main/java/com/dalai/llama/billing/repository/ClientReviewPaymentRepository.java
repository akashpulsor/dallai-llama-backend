package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClientReviewPaymentRepository extends JpaRepository<ClientReviewPayment, UUID> {

    Optional<ClientReviewPayment> findByGatewayOrderId(String gatewayOrderId);
}
