package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.ShotListJobStatus;
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

/**
 * One row per POST to /v1/projects/{projectId}/shots/generate-list. Tracks the async shot-list
 * generation job from PENDING to SUCCEEDED/FAILED. The UI polls this via the status endpoint;
 * {@link com.dalai.llama.preprod.kafka.ChatJobCompletedConsumer} flips it to a terminal state
 * on receipt of {@code llm.job.completed}.
 *
 * <p>{@code llmJobIdempotencyKey} is the correlation anchor between this local job row and the
 * llm-gateway job -- same string is sent to llm-gateway as its {@code Idempotency-Key}, and
 * carried through the completion event so the consumer can look up this row by it.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_list_job")
public class ShotListJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShotListJobStatus status;

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
