package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.IdeaGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdeaGenerationJobRepository extends JpaRepository<IdeaGenerationJob, UUID> {

    /** Used by {@link com.dalai.llama.creativeplanning.kafka.ChatJobCompletedConsumer} to find
     * which local row a completed event belongs to. Enforced-unique by the migration's
     * ux_idea_generation_job_llm_idempotency_key index. */
    Optional<IdeaGenerationJob> findByLlmJobIdempotencyKey(String key);

    /** Used by GET .../ideas/generate/latest to rehydrate a FAILED/PENDING run on page reload --
     * without this the UI would show the empty-options panel a fresh requirement shows, hiding
     * why generation actually failed or that it's still in-flight. */
    Optional<IdeaGenerationJob> findTopByProjectRequirementIdAndTenantIdOrderByCreatedAtDesc(
            UUID projectRequirementId, UUID tenantId);
}
