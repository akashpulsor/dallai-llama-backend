package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRequirementRepository extends JpaRepository<ProjectRequirement, UUID> {

    Optional<ProjectRequirement> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ProjectRequirement> findByShareToken(String shareToken);

    List<ProjectRequirement> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
