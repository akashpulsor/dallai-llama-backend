package com.dalai.llama.llmgateway.domain.entity;

import com.dalai.llama.llmgateway.domain.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_job")
public class LlmJob {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "schema_version", nullable = false, length = 16)
    private String schemaVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** When the current dispatch attempt started -- distinct from {@code createdAt} (first-ever
     * creation) so a retry's staleness clock doesn't inherit the original attempt's age. */
    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "last_error")
    private String lastError;

    /** The provider's output (video URI, chat text, etc.) for a COMPLETED job -- persisted so an
     * idempotent replay (see LlmGatewayService#handleExisting) can hand back the actual result,
     * not just confirm it happened. Null for any non-COMPLETED terminal state. */
    @Column(name = "result_content")
    private String resultContent;

    @Column(name = "callback_url")
    private String callbackUrl;
}
