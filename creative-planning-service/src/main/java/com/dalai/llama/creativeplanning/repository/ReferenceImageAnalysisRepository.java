package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.ReferenceImageAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferenceImageAnalysisRepository extends JpaRepository<ReferenceImageAnalysis, UUID> {

    Optional<ReferenceImageAnalysis> findByReferenceImageId(UUID referenceImageId);
}
