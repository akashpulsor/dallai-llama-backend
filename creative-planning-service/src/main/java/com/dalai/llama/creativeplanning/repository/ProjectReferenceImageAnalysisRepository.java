package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.ProjectReferenceImageAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectReferenceImageAnalysisRepository extends JpaRepository<ProjectReferenceImageAnalysis, UUID> {

    Optional<ProjectReferenceImageAnalysis> findByReferenceImageId(UUID referenceImageId);
}
