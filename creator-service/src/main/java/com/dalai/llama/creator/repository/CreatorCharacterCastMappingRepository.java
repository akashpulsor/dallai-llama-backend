package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorCharacterCastMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorCharacterCastMappingRepository extends JpaRepository<CreatorCharacterCastMapping, UUID> {

    List<CreatorCharacterCastMapping> findByTenantIdAndUserIdAndProjectIdOrderByCreatedAtAsc(
            String tenantId,
            String userId,
            UUID projectId
    );

    List<CreatorCharacterCastMapping> findByTenantIdAndUserIdAndLockedIdeaIdAndStoryIdeaIdOrderByCreatedAtAsc(
            String tenantId,
            String userId,
            UUID lockedIdeaId,
            UUID storyIdeaId
    );

    Optional<CreatorCharacterCastMapping> findByTenantIdAndUserIdAndLockedIdeaIdAndStoryIdeaIdAndCharacterKey(
            String tenantId,
            String userId,
            UUID lockedIdeaId,
            UUID storyIdeaId,
            String characterKey
    );
}
