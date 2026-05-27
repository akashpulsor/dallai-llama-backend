package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorScriptRepository extends JpaRepository<CreatorScript, UUID> {
    Optional<CreatorScript> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    Optional<CreatorScript> findTopByProjectIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(UUID projectId, String tenantId, String userId);

    Optional<CreatorScript> findTopByStoryIdeaIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(UUID storyIdeaId, String tenantId, String userId);

    Optional<CreatorScript> findTopByLockedIdeaIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(UUID lockedIdeaId, String tenantId, String userId);

    List<CreatorScript> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId, Pageable pageable);
}
