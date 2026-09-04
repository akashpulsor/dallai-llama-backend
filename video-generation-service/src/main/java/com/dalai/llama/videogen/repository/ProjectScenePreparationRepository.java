package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ProjectScenePreparation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectScenePreparationRepository extends JpaRepository<ProjectScenePreparation, UUID> {

    Optional<ProjectScenePreparation> findByProjectIdAndTenantId(UUID projectId, UUID tenantId);
}
