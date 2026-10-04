package com.dalai.llama.videogen.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One shot's path from its approved plan to a submitted source clip. See V39.
 *
 * <p>The prompt exists in three forms and they are never conflated: {@code aiRecommendedPrompt} is
 * what the model wrote, {@code userEditedPrompt} is the creator's saved draft (null until they save
 * one), and what was actually submitted lives on {@code shot_prompt} with the job it produced.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_generation_plan")
public class ShotGenerationPlan {

    @Id
    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "shot_ref", length = 64)
    private String shotRef;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "planned_duration_seconds", precision = 6, scale = 3)
    private BigDecimal plannedDurationSeconds;

    @Column(name = "assessed_at")
    private OffsetDateTime assessedAt;

    @Column(name = "shorter_generation_suitable")
    private Boolean shorterGenerationSuitable;

    @Column(name = "minimum_viable_duration_seconds", precision = 6, scale = 3)
    private BigDecimal minimumViableDurationSeconds;

    @Column(name = "recommended_duration_seconds")
    private Integer recommendedDurationSeconds;

    @Column(name = "recommended_fps")
    private Integer recommendedFps;

    @Column(name = "assessment_reasoning", columnDefinition = "TEXT")
    private String assessmentReasoning;

    @Column(name = "generation_duration_seconds")
    private Integer generationDurationSeconds;

    @Column(name = "generation_fps")
    private Integer generationFps;

    @Column(name = "timeline_duration_seconds")
    private Integer timelineDurationSeconds;

    @Column(name = "timeline_fps")
    private Integer timelineFps;

    @Column(name = "timeline_built_at")
    private OffsetDateTime timelineBuiltAt;

    @Column(name = "ai_recommended_prompt", columnDefinition = "TEXT")
    private String aiRecommendedPrompt;

    @Column(name = "prompt_duration_seconds")
    private Integer promptDurationSeconds;

    @Column(name = "prompt_fps")
    private Integer promptFps;

    @Column(name = "prompt_continuation_object_key", length = 1024)
    private String promptContinuationObjectKey;

    @Column(name = "prompt_composed_at")
    private OffsetDateTime promptComposedAt;

    @Column(name = "user_edited_prompt", columnDefinition = "TEXT")
    private String userEditedPrompt;

    @Builder.Default
    @Column(name = "draft_revision", nullable = false)
    private Integer draftRevision = 0;

    @Column(name = "draft_saved_at")
    private OffsetDateTime draftSavedAt;

    @Column(name = "continuation_source_shot_id")
    private UUID continuationSourceShotId;

    @Column(name = "continuation_frame_bucket", length = 128)
    private String continuationFrameBucket;

    @Column(name = "continuation_frame_object_key", length = 1024)
    private String continuationFrameObjectKey;

    @Column(name = "continuation_frame_timestamp_ms")
    private Long continuationFrameTimestampMs;

    @Column(name = "submitted_job_id")
    private UUID submittedJobId;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
