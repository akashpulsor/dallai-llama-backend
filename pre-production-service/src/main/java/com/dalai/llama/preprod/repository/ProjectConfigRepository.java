package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ProjectConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectConfigRepository extends JpaRepository<ProjectConfig, UUID> {

    Optional<ProjectConfig> findByProjectId(UUID projectId);
}
