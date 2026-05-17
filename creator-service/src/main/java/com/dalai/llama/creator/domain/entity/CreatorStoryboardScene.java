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
@Table(name = "creator_storyboard_scenes")
public class CreatorStoryboardScene {

    @Id
    private UUID id;

    @Column(name = "storyboard_id", nullable = false)
    private UUID storyboardId;

    @Column(name = "image_asset_id")
    private UUID imageAssetId;

    @Column(name = "shot_number", nullable = false)
    private Integer shotNumber;

    @Column(name = "start_time", length = 16)
    private String startTime;

    @Column(name = "end_time", length = 16)
    private String endTime;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(columnDefinition = "text")
    private String purpose;

    @Column(name = "shot_type", length = 120)
    private String shotType;

    @Column(name = "camera_angle", length = 160)
    private String cameraAngle;

    @Column(name = "camera_movement", length = 160)
    private String cameraMovement;

    @Column(name = "lens_suggestion", length = 160)
    private String lensSuggestion;

    @Column
    private Integer fps;

    @Column(columnDefinition = "text")
    private String composition;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> expression = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> emotion = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body_language", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> bodyLanguage = new LinkedHashMap<>();

    @Column(columnDefinition = "text")
    private String lighting;

    @Column(columnDefinition = "text")
    private String environment;

    @Column(columnDefinition = "text")
    private String action;

    @Column(name = "voice_over", columnDefinition = "text")
    private String voiceOver;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> dialogue = new LinkedHashMap<>();

    @Column(name = "text_overlay", columnDefinition = "text")
    private String textOverlay;

    @Column(length = 160)
    private String transition;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sound_design", nullable = false, columnDefinition = "jsonb")
    private List<String> soundDesign = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "editing_notes", nullable = false, columnDefinition = "jsonb")
    private List<String> editingNotes = new ArrayList<>();

    @Column(name = "retention_goal", columnDefinition = "text")
    private String retentionGoal;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "creator_direction", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> creatorDirection = new LinkedHashMap<>();

    @Column(name = "subtitle_position", length = 80)
    private String subtitlePosition;

    @Column(name = "mobile_focus_area", length = 120)
    private String mobileFocusArea;

    @Column(name = "safe_zone_notes", columnDefinition = "text")
    private String safeZoneNotes;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_difficulty", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> executionDifficulty = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cinematic_execution", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> cinematicExecution = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rookie_friendly_guide", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rookieFriendlyGuide = new LinkedHashMap<>();

    @Column(name = "sketch_prompt", columnDefinition = "text")
    private String sketchPrompt;

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
