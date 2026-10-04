package com.dalai.llama.postprod.domain.entity;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
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

/** One request to conform a shot's newest clip to its planned length. See V12. */
@Entity
@Table(name = "shot_clip_conform")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotClipConform {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "target_seconds", nullable = false, precision = 6, scale = 3)
    private BigDecimal targetSeconds;

    @Column(name = "interpolate", nullable = false)
    private Boolean interpolate;

    /** Same lifecycle as a frame extraction: QUEUED, PROCESSING, COMPLETED, FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FrameExtractionStatus status;

    @Column(name = "source_job_id")
    private UUID sourceJobId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
