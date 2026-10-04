package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotGenerationControls;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShotGenerationControlsRepository extends JpaRepository<ShotGenerationControls, UUID> {

    Optional<ShotGenerationControls> findByShotIdAndTenantId(UUID shotId, UUID tenantId);
}
