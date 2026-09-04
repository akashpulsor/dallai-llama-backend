package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.ProjectReferenceImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectReferenceImageRepository extends JpaRepository<ProjectReferenceImage, UUID> {

    List<ProjectReferenceImage> findByProjectRequirementId(UUID projectRequirementId);
}
