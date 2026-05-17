package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorIdea;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorIdeaRepository extends JpaRepository<CreatorIdea, UUID> {

    List<CreatorIdea> findTop20ByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);

    Optional<CreatorIdea> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    @Query(
            value = """
                    select *
                    from creator_ideas
                    where tenant_id = :tenantId
                      and user_id = :userId
                      and source = 'AI_FROM_LOCKED_BRIEF'
                      and selection_context ->> 'parentLockedIdeaId' = cast(:lockedIdeaId as text)
                    order by created_at asc
                    """,
            countQuery = """
                    select count(*)
                    from creator_ideas
                    where tenant_id = :tenantId
                      and user_id = :userId
                      and source = 'AI_FROM_LOCKED_BRIEF'
                      and selection_context ->> 'parentLockedIdeaId' = cast(:lockedIdeaId as text)
                    """,
            nativeQuery = true
    )
    Page<CreatorIdea> findGeneratedIdeasForLockedBrief(
            @Param("lockedIdeaId") UUID lockedIdeaId,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            Pageable pageable
    );
}
