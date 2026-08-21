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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32)
    private ApprovalStatus approvalStatus;

    @Column(name = "llm_gateway_job_id")
    private String llmGatewayJobId;

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
