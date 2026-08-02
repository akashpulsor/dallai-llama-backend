package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorAvatarSceneDialogue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorAvatarSceneDialogueRepository extends JpaRepository<CreatorAvatarSceneDialogue, UUID> {

    List<CreatorAvatarSceneDialogue> findByTenantIdAndUserIdAndVideoRunIdAndCurrentTrueOrderBySceneNumberAscSequenceNumberAsc(
            String tenantId,
            String userId,
            UUID videoRunId
    );

    Optional<CreatorAvatarSceneDialogue> findFirstByTenantIdAndUserIdAndVideoRunIdAndSceneNumberAndSourceTrueAndCurrentTrue(
            String tenantId,
            String userId,
            UUID videoRunId,
            Integer sceneNumber
    );

    Optional<CreatorAvatarSceneDialogue> findFirstByRootDialogueIdAndLanguageKeyAndCurrentTrue(
            UUID rootDialogueId,
            String languageKey
    );

    List<CreatorAvatarSceneDialogue> findByRootDialogueIdAndCurrentTrueOrderByCreatedAtAsc(
            UUID rootDialogueId
    );

    @Query("""
            select max(dialogue.versionNumber)
            from CreatorAvatarSceneDialogue dialogue
            where dialogue.videoRunId = :videoRunId
              and dialogue.sceneNumber = :sceneNumber
            """)
    Integer findMaxVersionNumber(
            @Param("videoRunId") UUID videoRunId,
            @Param("sceneNumber") Integer sceneNumber
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update CreatorAvatarSceneDialogue dialogue
               set dialogue.current = false,
                   dialogue.updatedAt = :updatedAt
             where dialogue.rootDialogueId = :rootDialogueId
               and dialogue.current = true
            """)
    int retireLineage(
            @Param("rootDialogueId") UUID rootDialogueId,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update CreatorAvatarSceneDialogue dialogue
               set dialogue.current = false,
                   dialogue.updatedAt = :updatedAt
             where dialogue.rootDialogueId = :rootDialogueId
               and dialogue.languageKey = :languageKey
               and dialogue.current = true
            """)
    int retireLanguageVariant(
            @Param("rootDialogueId") UUID rootDialogueId,
            @Param("languageKey") String languageKey,
            @Param("updatedAt") OffsetDateTime updatedAt
    );
}
