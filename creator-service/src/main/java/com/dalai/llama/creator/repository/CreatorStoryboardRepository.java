package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorStoryboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreatorStoryboardRepository extends JpaRepository<CreatorStoryboard, UUID> {

    Optional<CreatorStoryboard> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);
}
