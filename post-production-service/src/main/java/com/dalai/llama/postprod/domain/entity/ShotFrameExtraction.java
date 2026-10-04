package com.dalai.llama.postprod.domain.entity;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
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

import java.time.OffsetDateTime;
import java.util.UUID;

/** One request to take frames from a shot's current cut, and how it went. See V11. */
@Entity
@Table(name = "shot_frame_extraction")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotFrameExtraction {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private FrameExtractionMode mode;

    @Column(name = "sample_fps")
    private Integer sampleFps;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FrameExtractionStatus status;

    @Column(name = "clip_version_id")
    private UUID clipVersionId;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
