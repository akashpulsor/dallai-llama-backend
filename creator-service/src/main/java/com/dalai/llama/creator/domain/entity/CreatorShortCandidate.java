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

import java.math.BigDecimal;
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
@Table(name = "creator_short_candidates")
public class CreatorShortCandidate {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(name = "generation_job_id")
    private UUID generationJobId;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "rank_index", nullable = false)
    private Integer rankIndex;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(precision = 6, scale = 3)
    private BigDecimal score;

    @Column(name = "hook_type", length = 80)
    private String hookType;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edit_decision_list", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> editDecisionList = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "caption_plan", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> captionPlan = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "render_manifest", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> renderManifest = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (rankIndex == null) {
            rankIndex = 1;
        }
        if (title == null || title.isBlank()) {
            title = "Candidate " + rankIndex;
        }
        if (status == null || status.isBlank()) {
            status = "READY_FOR_REVIEW";
        }
        if (reviewStatus == null || reviewStatus.isBlank()) {
            reviewStatus = "PENDING";
        }
        if (editDecisionList == null) {
            editDecisionList = new LinkedHashMap<>();
        }
        if (captionPlan == null) {
            captionPlan = new LinkedHashMap<>();
        }
        if (renderManifest == null) {
            renderManifest = new LinkedHashMap<>();
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
        if (editDecisionList == null) {
            editDecisionList = new LinkedHashMap<>();
        }
        if (captionPlan == null) {
            captionPlan = new LinkedHashMap<>();
        }
        if (renderManifest == null) {
            renderManifest = new LinkedHashMap<>();
        }
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
    }
}