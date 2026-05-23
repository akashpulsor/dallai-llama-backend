package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorAudience;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorAudienceRepository extends JpaRepository<CreatorAudience, UUID> {

    List<CreatorAudience> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);

    List<CreatorAudience> findByProjectIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(UUID projectId, String tenantId, String userId);

    Optional<CreatorAudience> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);
}
