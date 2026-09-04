package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.ApprovalStatus;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "video_gen_job")
public class VideoGenJob {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "shot_ref", nullable = false, length = 128)
    private String shotRef;

    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    /** Captured from {@code ShotContext.technical()} at generate() time so approve()'s later
     * dispatch call can actually forward them to the provider -- previously dropped entirely. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "aspect_ratio", length = 16)
    private String aspectRatio;

    /** Same capture-at-generate()/read-at-approve() reasoning as durationSeconds/aspectRatio --
     * the project's voice-clone model pin (ShotContext.technical().voiceCloneModel(), sourced
     * from pre-production-service's ProjectConfig.preferredVoiceModel), forwarded to
     * BeatDubbingService.dub() when this job auto-dubs. Null uses that service's own default. */
    @Column(name = "voice_clone_model")
    private String voiceCloneModel;

    /** True when every dialogue beat this job carried resolved a cast voice reference -- decided
     * once at generate() time (see {@link BeatDubbingService#canAutoDub}), read back at approve()
     * to both mute Seedance's native audio and trigger the post-dispatch dub/mux step. */
    @Column(name = "mute_audio", nullable = false)
    @Builder.Default
    private boolean muteAudio = false;

    /** Null = no auto-dub attempted; true = the beat-matched dub/mux succeeded; false = attempted
     * but failed (output is the plain silent video). See {@link
     * com.dalai.llama.videogen.service.BeatDubbingService}. */
    @Column(name = "dub_succeeded")
    private Boolean dubSucceeded;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32)
    private ApprovalStatus approvalStatus;

    @Column(name = "llm_gateway_job_id")
    private String llmGatewayJobId;

    /** Deterministic seed sent to the provider on dispatch. Derived from the project's
     * {@code locked_idea_id} (see {@code ShotGenerationOrchestrator.resolveSeed}) so every shot
     * in the same project shares one seed, giving the provider a single reproducible starting
     * point across scenes -- the primary continuity lever for character faces, set details, and
     * lighting. Persisted so a rerun of the same job uses the same seed and yields the same
     * output (short of the provider randomizing internally). */
    @Column(name = "seed_used")
    private Long seedUsed;

    @Column(name = "output_uri")
    private String outputUri;

    @Column(name = "output_bucket")
    private String outputBucket;

    @Column(name = "output_object_key")
    private String outputObjectKey;

    @Column(name = "estimated_cost", precision = 18, scale = 10)
    private BigDecimal estimatedCost;

    @Column(name = "actual_cost", precision = 18, scale = 10)
    private BigDecimal actualCost;

    @Column(name = "cost_currency", length = 8)
    private String costCurrency;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
