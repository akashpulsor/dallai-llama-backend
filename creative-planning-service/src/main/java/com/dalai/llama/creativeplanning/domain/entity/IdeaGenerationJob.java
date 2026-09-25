package com.dalai.llama.creativeplanning.domain.entity;

import com.dalai.llama.creativeplanning.domain.IdeaGenerationJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row per POST to {@code /v1/project-requirements/{id}/ideas/generate}. Tracks the async
 * idea-generation job from PENDING to SUCCEEDED/FAILED. The UI polls the status endpoint;
 * {@link com.dalai.llama.creativeplanning.kafka.ChatJobCompletedConsumer} flips it terminal on
 * receipt of {@code llm.job.completed}. Same shape as pre-production-service's ShotListJob --
 * see that class's javadoc for the idempotency-key correlation contract. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "idea_generation_job")
public class IdeaGenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_requirement_id", nullable = false)
    private UUID projectRequirementId;

    /** What the caller asked for -- consumer needs this to persist the right number of options
     * (which the LLM prompt was also seeded with). Nullable when the caller accepts the default. */
    @Column(name = "requested_option_count")
    private Integer requestedOptionCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IdeaGenerationJobStatus status;

    @Column(name = "llm_job_idempotency_key", nullable = false, length = 255)
    private String llmJobIdempotencyKey;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
