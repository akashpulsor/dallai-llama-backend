package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.StepContinuityAnalysisRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StepContinuityAnalysisRepository extends JpaRepository<StepContinuityAnalysisRecord, UUID> {

    Optional<StepContinuityAnalysisRecord> findByShotIdAndSourceShotIdAndKind(UUID shotId, UUID sourceShotId, ShotImageKind kind);
}
