package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.ProjectReferenceVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectReferenceVideoRepository extends JpaRepository<ProjectReferenceVideo, UUID> {

    List<ProjectReferenceVideo> findByProjectRequirementId(UUID projectRequirementId);
}
