package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorStoryboard;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorStoryboardRepository extends JpaRepository<CreatorStoryboard, UUID> {

    Optional<CreatorStoryboard> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    Optional<CreatorStoryboard> findTopByProjectIdAndIdeaIdAndTenantIdAndUserIdOrderByCreatedAtDesc(
            UUID projectId,
            UUID ideaId,
            String tenantId,
            String userId
    );

    Optional<CreatorStoryboard> findTopByIdeaIdAndTenantIdAndUserIdOrderByCreatedAtDesc(UUID ideaId, String tenantId, String userId);

    List<CreatorStoryboard> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId, Pageable pageable);
}
