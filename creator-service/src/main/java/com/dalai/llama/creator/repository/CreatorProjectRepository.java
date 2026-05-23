package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorProject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorProjectRepository extends JpaRepository<CreatorProject, UUID> {

    Optional<CreatorProject> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    List<CreatorProject> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId, Pageable pageable);
}
