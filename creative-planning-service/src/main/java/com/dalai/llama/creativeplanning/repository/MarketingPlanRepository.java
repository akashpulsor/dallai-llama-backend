package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.MarketingPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketingPlanRepository extends JpaRepository<MarketingPlan, UUID> {

    Optional<MarketingPlan> findByIdAndTenantId(UUID id, UUID tenantId);

    List<MarketingPlan> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
