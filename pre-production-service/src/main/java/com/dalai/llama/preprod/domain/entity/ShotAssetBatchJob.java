package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.joblifecycle.BatchJobRecord;
import com.dalai.llama.joblifecycle.JobLifecycleStatus;
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

/** One "Generate all shot assets" run for a project: lighting plan, camera plan, and all 4 image
 * kinds for every camera-planned shot (everything except MOTION_GRAPHIC, which already gets its
 * own plan auto-wired at shot-list time and has no camera/lighting to speak of -- see ShotType's
 * javadoc). {@link #projectId} is this entity's own name for {@link BatchJobRecord#getOwnerId()}
 * -- kept as {@code projectId} in this schema since that's what it actually is here, the generic
 * interface method just returns it. The queue/retry/DLQ algorithm itself lives in job-lifecycle-
 * common's {@code BatchWorker} (see {@code ShotAssetBatchWorker} for the thin wiring); this class
 * only owns the schema. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_asset_batch_job")
public class ShotAssetBatchJob implements BatchJobRecord {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private JobLifecycleStatus status;

    @Column(name = "total_steps", nullable = false)
    private Integer totalSteps;

    @Column(name = "completed_steps", nullable = false)
    @Builder.Default
    private Integer completedSteps = 0;

    @Column(name = "current_step_index", nullable = false)
    @Builder.Default
    private Integer currentStepIndex = 0;

    @Column(name = "current_step_attempt", nullable = false)
    @Builder.Default
    private Integer currentStepAttempt = 0;

    @Column(name = "error_count", nullable = false)
    @Builder.Default
    private Integer errorCount = 0;

    @Column(name = "current_step_label", columnDefinition = "text")
    private String currentStepLabel;

    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Override
    public UUID getJobId() {
        return id;
    }

    @Override
    public UUID getOwnerId() {
        return projectId;
    }
}
