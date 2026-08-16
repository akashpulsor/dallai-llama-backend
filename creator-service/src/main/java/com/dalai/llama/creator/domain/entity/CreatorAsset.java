package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "creator_assets")
public class CreatorAsset {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "storyboard_id")
    private UUID storyboardId;

    @Column(name = "asset_type", nullable = false, length = 64)
    private String assetType;

    @Column(nullable = false, length = 160)
    private String bucket;

    @Column(name = "object_key", nullable = false, columnDefinition = "text")
    private String objectKey;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "public_url", columnDefinition = "text")
    private String publicUrl;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "script_id")
    private UUID scriptId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "shot_number")
    private Integer shotNumber;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String status = "READY";

    @Builder.Default
    @Column(nullable = false)
    private boolean accepted = false;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "accepted_by", length = 128)
    private String acceptedBy;

    @Builder.Default
    @Column(name = "is_combined", nullable = false)
    private boolean combined = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        if (status == null || status.isBlank()) {
            status = "READY";
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
