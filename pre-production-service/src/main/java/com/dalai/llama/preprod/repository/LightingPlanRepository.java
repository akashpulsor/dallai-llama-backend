package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.LightingPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LightingPlanRepository extends JpaRepository<LightingPlan, UUID> {

    Optional<LightingPlan> findByShotId(UUID shotId);

    List<LightingPlan> findByTenantIdAndShotIdIn(UUID tenantId, Collection<UUID> shotIds);
}
