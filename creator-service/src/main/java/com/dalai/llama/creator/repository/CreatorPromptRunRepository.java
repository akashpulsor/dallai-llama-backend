package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CreatorPromptRunRepository extends JpaRepository<CreatorPromptRun, UUID> {

    Optional<CreatorPromptRun> findTop1ByPromptTemplateKeyAndStatusOrderByCreatedAtDesc(String promptTemplateKey, String status);

    Optional<CreatorPromptRun> findTop1ByPromptTemplateKeyAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(
            String promptTemplateKey,
            String status,
            OffsetDateTime completedAfter
    );
}
