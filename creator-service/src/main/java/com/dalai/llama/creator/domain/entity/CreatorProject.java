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
@Table(name = "creator_projects")
public class CreatorProject {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "selected_platform_code", length = 64)
    private String selectedPlatformCode;

    @Column(name = "selected_category_code", length = 64)
    private String selectedCategoryCode;

    @Column(nullable = false, length = 64)
    private String timeframe;

    @Column(name = "country_code", nullable = false, length = 16)
    private String countryCode;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "selected_trend_id")
    private UUID selectedTrendId;

    @Column(name = "selected_audience_id")
    private UUID selectedAudienceId;

    @Column(name = "selected_profile_id")
    private UUID selectedProfileId;

    @Column(name = "selected_idea_id")
    private UUID selectedIdeaId;

    @Column(name = "selected_storyboard_id")
    private UUID selectedStoryboardId;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> preferences = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memory_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> memorySnapshot = new LinkedHashMap<>();

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
        if (status == null || status.isBlank()) {
            status = "DRAFT";
        }
        if (timeframe == null || timeframe.isBlank()) {
            timeframe = "LAST_7_DAYS";
        }
        if (countryCode == null || countryCode.isBlank()) {
            countryCode = "IN";
        }
        if (preferences == null) {
            preferences = new LinkedHashMap<>();
        }
        if (memorySnapshot == null) {
            memorySnapshot = new LinkedHashMap<>();
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
    }
}
