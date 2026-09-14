package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.PrepareBatchJobStatus;
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

/** One prepare-batch request, durable from the moment the endpoint accepts it. The UI polls this
 * for progress and a batch stranded by a lost Kafka event stays visible as PENDING rather than
 * silently never happening. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "prepare_batch_job")
public class PrepareBatchJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PrepareBatchJobStatus status;

    /** Comma-separated shot ids, or null for "every shot in the project". */
    @Column(name = "shot_ids", columnDefinition = "text")
    private String shotIds;

    @Column(name = "model_pin", length = 128)
    private String modelPin;

    @Column(name = "resolution_override", length = 16)
    private String resolutionOverride;

    @Column(name = "dialogue_flag", length = 8)
    private String dialogueFlag;

    @Column(name = "captions_flag", length = 8)
    private String captionsFlag;

    @Column(name = "prepared_count", nullable = false)
    private Integer preparedCount;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount;

    /** Shots this batch will attempt. Null until the consumer resolves it from the bundle. */
    @Column(name = "total_count")
    private Integer totalCount;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
