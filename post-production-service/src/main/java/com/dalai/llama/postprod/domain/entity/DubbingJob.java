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

/** The standalone "dub any uploaded video" flow -- not tied to a project/shot/pre-production
 * data at all, unlike PostProductionJob. Whole-video-as-one-unit for v1 (see
 * DubbingOrchestrator's class comment for the named per-minute-segmentation gap this leaves). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "dubbing_job")
public class DubbingJob {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "source_bucket", nullable = false)
    private String sourceBucket;

    @Column(name = "source_object_key", nullable = false)
    private String sourceObjectKey;

    @Column(name = "target_language", nullable = false, length = 16)
    private String targetLanguage;

    @Column(name = "source_language", length = 16)
    private String sourceLanguage;

    @Column(name = "transcript", columnDefinition = "text")
    private String transcript;

    @Column(name = "translated_transcript", columnDefinition = "text")
    private String translatedTranscript;

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
