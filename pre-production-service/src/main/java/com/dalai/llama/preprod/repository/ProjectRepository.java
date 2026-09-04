package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Project> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Project> findByLockedIdeaIdAndTenantId(UUID lockedIdeaId, UUID tenantId);

    Optional<Project> findByClientReviewToken(String clientReviewToken);
}
