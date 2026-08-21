package com.dalai.llama.postprod.domain.entity;

import com.dalai.llama.postprod.domain.PostProductionStatus;
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

/** The actual voice-clone (if needed) + lip-sync execution record for one shot -- kept as its
 * own row (not folded into PostProductionJob) because it tracks two separate provider dispatches
 * with their own gateway job ids, and post-production's own job status is broader than just this
 * one step (room for foley/music/mix-plan to land as siblings later without reshaping this). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "dialogue_sync_job")
public class DialogueSyncJob {

    @Id
    @Column(name = "dialogue_sync_job_id")
    private UUID dialogueSyncJobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "post_production_job_id", nullable = false)
    private UUID postProductionJobId;

    @Column(name = "shot_ref", nullable = false, length = 128)
    private String shotRef;

    @Column(name = "source_language", length = 16)
    private String sourceLanguage;

    @Column(name = "target_language", length = 16)
    private String targetLanguage;

    /** true when source and target language match -- lip-sync only, no voice clone/dub step. */
    @Column(name = "same_language", nullable = false)
    private Boolean sameLanguage;

    @Column(name = "voice_profile_id")
    private UUID voiceProfileId;

    @Column(name = "llm_gateway_voice_clone_job_id")
    private String llmGatewayVoiceCloneJobId;

    @Column(name = "llm_gateway_lip_sync_job_id")
    private String llmGatewayLipSyncJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PostProductionStatus status;

    @Column(name = "output_bucket")
    private String outputBucket;

    @Column(name = "output_object_key")
    private String outputObjectKey;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
