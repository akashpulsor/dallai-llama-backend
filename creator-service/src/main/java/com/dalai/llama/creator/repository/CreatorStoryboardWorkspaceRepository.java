package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorStoryboardWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorStoryboardWorkspaceRepository extends JpaRepository<CreatorStoryboardWorkspace, UUID> {

    List<CreatorStoryboardWorkspace> findByScriptIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(
            UUID scriptId, String tenantId, String userId);

    Optional<CreatorStoryboardWorkspace> findByScriptIdAndTenantIdAndUserIdAndStatus(
            UUID scriptId, String tenantId, String userId, String status);

    Optional<CreatorStoryboardWorkspace> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);
}
