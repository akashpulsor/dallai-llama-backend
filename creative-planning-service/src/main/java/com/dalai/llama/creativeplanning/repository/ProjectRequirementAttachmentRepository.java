package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirementAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectRequirementAttachmentRepository extends JpaRepository<ProjectRequirementAttachment, UUID> {

    List<ProjectRequirementAttachment> findByRequirementId(UUID requirementId);
}
