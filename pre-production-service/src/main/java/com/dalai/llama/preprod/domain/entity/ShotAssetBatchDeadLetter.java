package com.dalai.llama.preprod.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One step that failed job-lifecycle-common's {@code BatchWorker.maxAttempts} (3, see
 * {@code ShotAssetBatchWorker}) consecutive times during a batch run -- the worker moves past it
 * rather than retrying forever, but the failure lands here instead of being silently dropped.
 * {@code step} is {@link com.dalai.llama.preprod.service.ShotAssetStep.Kind#name()}, not a JPA
 * enum column, so this table never needs a migration if that enum ever grows. Never auto-retried
 * -- only a direct call to {@code ShotAssetBatchDeadLetterService#retry} against one row's id runs
 * that step again, same one-step-at-a-time dispatch the worker itself uses. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_asset_batch_dead_letter")
public class ShotAssetBatchDeadLetter {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "batch_job_id", nullable = false)
    private UUID batchJobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "step", nullable = false, length = 32)
    private String step;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "resolved", nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
