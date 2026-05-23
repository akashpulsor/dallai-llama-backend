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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_script_shots")
public class CreatorScriptShot {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

    @Column(name = "story_idea_id")
    private UUID storyIdeaId;

    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    @Column(name = "scene_number")
    private Integer sceneNumber;

    @Column(name = "shot_number", nullable = false)
    private Integer shotNumber;

    @Column(name = "beat_number")
    private Integer beatNumber;

    @Column(name = "beat_title", length = 240)
    private String beatTitle;

    @Column(name = "start_time")
    private Double startTime;

    @Column(name = "end_time")
    private Double endTime;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(length = 240)
    private String title;

    @Column(columnDefinition = "text")
    private String purpose;

    @Column(name = "shot_type", length = 64)
    private String shotType;

    @Column(name = "coverage_type", length = 64)
    private String coverageType;

    @Column(name = "screen_direction", length = 64)
    private String screenDirection;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "primary_characters", nullable = false, columnDefinition = "jsonb")
    private List<String> primaryCharacters = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "side_characters", nullable = false, columnDefinition = "jsonb")
    private List<String> sideCharacters = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "primary_actors", nullable = false, columnDefinition = "jsonb")
    private List<String> primaryActors = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "side_actors", nullable = false, columnDefinition = "jsonb")
    private List<String> sideActors = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> dialogue = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shot_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> shotPayload = new LinkedHashMap<>();

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
        if (primaryCharacters == null) {
            primaryCharacters = new ArrayList<>();
        }
        if (sideCharacters == null) {
            sideCharacters = new ArrayList<>();
        }
        if (primaryActors == null) {
            primaryActors = new ArrayList<>();
        }
        if (sideActors == null) {
            sideActors = new ArrayList<>();
        }
        if (dialogue == null) {
            dialogue = new LinkedHashMap<>();
        }
        if (shotPayload == null) {
            shotPayload = new LinkedHashMap<>();
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
