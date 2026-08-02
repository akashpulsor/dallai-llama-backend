package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_short_videos")
public class CreatorShortVideo {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "source_asset_id")
    private UUID sourceAssetId;

    @Column(name = "generation_job_id")
    private UUID generationJobId;

    @Column(length = 240)
    private String title;

    @Column(name = "original_file_name", length = 260)
    private String originalFileName;

    @Column(length = 64)
    private String platform;

    @Column(name = "target_duration_seconds")
    private Integer targetDurationSeconds;

    @Column(name = "requested_shorts")
    private Integer requestedShorts;

    @Column(name = "review_mode", length = 32)
    private String reviewMode;

    @Column(nullable = false, length = 32)
    private String status;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "video_dna", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> videoDna = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transcript_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> transcriptPayload = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> graphPayload = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trace_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> tracePayload = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null || status.isBlank()) {
            status = "UPLOADED";
        }
        if (settings == null) {
            settings = new LinkedHashMap<>();
        }
        if (videoDna == null) {
            videoDna = new LinkedHashMap<>();
        }
        if (transcriptPayload == null) {
            transcriptPayload = new LinkedHashMap<>();
        }
        if (graphPayload == null) {
            graphPayload = new LinkedHashMap<>();
        }
        if (tracePayload == null) {
            tracePayload = new LinkedHashMap<>();
        }
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
        if (settings == null) {
            settings = new LinkedHashMap<>();
        }
        if (videoDna == null) {
            videoDna = new LinkedHashMap<>();
        }
        if (transcriptPayload == null) {
            transcriptPayload = new LinkedHashMap<>();
        }
        if (graphPayload == null) {
            graphPayload = new LinkedHashMap<>();
        }
        if (tracePayload == null) {
            tracePayload = new LinkedHashMap<>();
        }
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
    }
}