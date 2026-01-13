package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    List<PaymentMethod> findByTenantIdAndActiveTrue(UUID tenantId);
}
