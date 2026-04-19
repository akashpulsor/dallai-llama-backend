package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    List<PaymentEvent> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    List<PaymentEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}