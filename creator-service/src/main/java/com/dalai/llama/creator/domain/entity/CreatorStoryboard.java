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
@Table(name = "creator_storyboards")
public class CreatorStoryboard {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "idea_id")
    private UUID ideaId;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;

    @Column(name = "total_shots", nullable = false)
    private Integer totalShots;

    @Column(name = "pacing_style", columnDefinition = "text")
    private String pacingStyle;

    @Column(name = "emotional_arc", columnDefinition = "text")
    private String emotionalArc;

    @Column(name = "hook_strategy", columnDefinition = "text")
    private String hookStrategy;

    @Column(name = "creator_fit_reasoning", columnDefinition = "text")
    private String creatorFitReasoning;

    @Column(name = "audience_fit_reasoning", columnDefinition = "text")
    private String audienceFitReasoning;

    @Column(name = "overall_execution_difficulty", length = 120)
    private String overallExecutionDifficulty;

    @Column(nullable = false, length = 32)
    private String status;

    @Builder.Default
    @Column(nullable = false)
    private boolean saved = false;

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
        if (status == null || status.isBlank()) {
            status = "GENERATED";
        }
        if (totalShots == null) {
            totalShots = 0;
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
    }
}
