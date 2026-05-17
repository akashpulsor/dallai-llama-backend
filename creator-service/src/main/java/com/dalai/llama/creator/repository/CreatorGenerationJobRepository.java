package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorGenerationJobRepository extends JpaRepository<CreatorGenerationJob, UUID> {

    Optional<CreatorGenerationJob> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    List<CreatorGenerationJob> findTop20ByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId);
}
