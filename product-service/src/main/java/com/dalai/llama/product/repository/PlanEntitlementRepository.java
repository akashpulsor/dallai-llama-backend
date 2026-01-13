package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.PlanEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, UUID> {

    Optional<PlanEntitlement> findByPlan_Id(UUID planId);
}
