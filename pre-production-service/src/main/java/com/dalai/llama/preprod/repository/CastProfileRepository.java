package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.CastProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CastProfileRepository extends JpaRepository<CastProfile, UUID> {

    Optional<CastProfile> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Library entries ({@code project_id IS NULL}) plus anything scoped to this project. */
    List<CastProfile> findByTenantIdAndProjectIdIsNullOrTenantIdAndProjectId(
            UUID tenantIdForLibrary, UUID tenantIdForProject, UUID projectId);
}
