package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.JobStatus;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Aggregate render across every completed shot in a project: the "final deliverable" the
 * client watches. One row per assembly attempt; latest per project is the one the UI
 * surfaces. Status transitions mirror {@code VideoGenJob}'s ({@code JobStatus} reused) so
 * poll/reconcile helpers can treat both uniformly.
 *
 * <p>Not versioned per-shot the way {@link ShotPrompt} is -- a re-assembly overwrites the
 * previous latest by creating a new row; nothing depends on old renders being addressable
 * beyond their raw MinIO objects (which are keyed by renderId and stay resolvable).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "final_render_job")
public class FinalRenderJob {

    @Id
    @Column(name = "render_id")
    private UUID renderId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;

    /** MinIO location of the merged mp4 -- null until COMPLETED. */
    @Column(name = "output_bucket")
    private String outputBucket;

    @Column(name = "output_object_key")
    private String outputObjectKey;

    /** How many source shots went into this render -- lets a UI show "N shots merged" without
     * a second call, and lets ops spot obviously-truncated renders (e.g. a project that
     * should have had 12 shots but rendered as 3). */
    @Column(name = "shot_count", nullable = false)
    @Builder.Default
    private int shotCount = 0;

    /** Comma-separated VideoGenJob.jobId list, in the order they were concatenated -- a
     * lightweight audit trail. A join table is deliberately overkill for a set that's
     * write-once at assembly time and only ever read back as a whole. */
    @Column(name = "source_job_ids", length = 4096)
    private String sourceJobIds;

    /** Null (self-hosted ffmpeg, zero marginal cost) -- kept for future telemetry
     * consistency with {@link VideoGenJob#getActualCost}. */
    @Column(name = "actual_cost", precision = 18, scale = 10)
    private BigDecimal actualCost;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
