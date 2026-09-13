package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.CreatorVideoPlanEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreatorVideoPlanEntitlementRepository extends JpaRepository<CreatorVideoPlanEntitlement, UUID> {

    Optional<CreatorVideoPlanEntitlement> findByPlan_Id(UUID planId);

    Optional<CreatorVideoPlanEntitlement> findByPlan_Code(String planCode);
}
