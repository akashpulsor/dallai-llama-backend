package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.joblifecycle.TrackedJob;
import com.dalai.llama.preprod.domain.GenerationJobType;
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

/** Tracks a dispatch to a downstream generation service (video-generation-service today) for one
 * shot -- built on job-lifecycle-common from day one rather than hand-rolling this a fourth time
 * (see the design doc's §12 DRY fix). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "generation_job")
public class GenerationJob implements TrackedJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 32)
    private GenerationJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private JobLifecycleStatus status;

    @Column(name = "external_job_id")
    private UUID externalJobId;

    @Column(name = "external_prompt_id")
    private UUID externalPromptId;

    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Override
    public UUID getJobId() {
        return id;
    }
}
