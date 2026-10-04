package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotGenerationPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShotGenerationPlanRepository extends JpaRepository<ShotGenerationPlan, UUID> {

    Optional<ShotGenerationPlan> findByTenantIdAndShotId(UUID tenantId, UUID shotId);
}
