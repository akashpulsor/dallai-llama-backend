package com.dalai.llama.postprod.domain.entity;

import com.dalai.llama.postprod.domain.PostProductionScope;
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

/** One row per shot going through post-production, whether it was requested one at a time
 * (scope=SHOT) or as part of moving a whole finished project over at once (scope=PROJECT --
 * still one row per shot, just created together as a batch; there is no separate parent/batch
 * record because nothing yet needs to query "the batch" as its own thing, only "this project's
 * shots" via project_id, which every row already carries). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "post_production_job")
public class PostProductionJob {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PostProductionScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PostProductionStatus status;

    /** The video-generation-service job this post-production job is processing the output of --
     * the source shot must already be COMPLETED there before this can run. */
    @Column(name = "video_gen_job_id", nullable = false)
    private UUID videoGenJobId;

    @Column(name = "dialogue_sync_job_id")
    private UUID dialogueSyncJobId;

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
