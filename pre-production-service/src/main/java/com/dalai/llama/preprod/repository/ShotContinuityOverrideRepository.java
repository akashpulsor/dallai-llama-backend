package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotContinuityOverride;
import com.dalai.llama.preprod.service.continuity.VisualField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotContinuityOverrideRepository extends JpaRepository<ShotContinuityOverride, UUID> {

    List<ShotContinuityOverride> findByShotId(UUID shotId);

    Optional<ShotContinuityOverride> findByShotIdAndField(UUID shotId, VisualField field);
}
