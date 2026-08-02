package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorScriptRepository extends JpaRepository<CreatorScript, UUID> {
    Optional<CreatorScript> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select script from CreatorScript script where script.id = :scriptId and script.tenantId = :tenantId and script.userId = :userId")
    Optional<CreatorScript> findByIdAndTenantIdAndUserIdForUpdate(
            @Param("scriptId") UUID scriptId,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId
    );

    Optional<CreatorScript> findTopByProjectIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(UUID projectId, String tenantId, String userId);

    Optional<CreatorScript> findTopByStoryIdeaIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(UUID storyIdeaId, String tenantId, String userId);

    Optional<CreatorScript> findTopByLockedIdeaIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(UUID lockedIdeaId, String tenantId, String userId);

    List<CreatorScript> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId, Pageable pageable);
}
