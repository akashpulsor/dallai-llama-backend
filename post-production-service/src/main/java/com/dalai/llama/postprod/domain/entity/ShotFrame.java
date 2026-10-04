package com.dalai.llama.postprod.domain.entity;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
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

/** One frame taken from one cut of a shot. See V11. */
@Entity
@Table(name = "shot_frame")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotFrame {

    @Id
    @Column(name = "frame_id")
    private UUID frameId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "clip_version_id", nullable = false)
    private UUID clipVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private FrameExtractionMode mode;

    @Column(name = "frame_number")
    private Long frameNumber;

    @Column(name = "timestamp_ms", nullable = false)
    private Long timestampMs;

    @Column(name = "bucket", nullable = false, length = 128)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
