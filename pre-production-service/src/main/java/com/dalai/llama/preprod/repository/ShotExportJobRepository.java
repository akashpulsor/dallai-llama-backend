package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotExportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotExportJobRepository extends JpaRepository<ShotExportJob, UUID> {

    /** History for a project's export panel: newest first. */
    List<ShotExportJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** Tenant-scoped read used by the download-by-id endpoint (id alone would be enough to find
     * the row but the tenant guard stops one tenant handing another tenant's id and getting a
     * signed URL back for their export). */
    Optional<ShotExportJob> findByIdAndTenantId(UUID id, UUID tenantId);
}
