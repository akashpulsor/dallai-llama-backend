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
@Table(name = "creator_scripts")
/**
 * Saved cinematic script planning JSON generated from a selected story idea.
 */
public class CreatorScript {

    /** Primary script id returned to the frontend. */
    @Id
    private UUID id;

    /** Tenant or organization identifier from auth/platform context. */
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /** Creator user identifier from auth context. */
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    /** Project associated with this script, when available. */
    @Column(name = "project_id")
    private UUID projectId;

    /** Locked trend/original brief used as the root source. */
    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

    /** Saved story idea that this script expands. */
    @Column(name = "story_idea_id")
    private UUID storyIdeaId;

    /** Prompt run used to generate this script. */
    @Column(name = "prompt_run_id")
    private UUID promptRunId;

    /** Topic category used for script conditioning. */
    @Column(name = "category_code", length = 64)
    private String categoryCode;

    /** Target short duration in seconds. */
    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;

    @Column(name = "format_tier", length = 40)
    private String formatTier;

    @Column(name = "act_structure", length = 64)
    private String actStructure;

    @Column(name = "budget_tier", length = 40)
    private String budgetTier;

    @Column(name = "total_shots")
    private Integer totalShots;

    @Column(name = "scene_count")
    private Integer sceneCount;

    @Column(name = "sequence_count")
    private Integer sequenceCount;

    /** Dialogue output language requested for generated spoken lines and overlays. */
    @Column(name = "dialogue_language", length = 64)
    private String dialogueLanguage;

    /** Target screen orientation such as vertical or horizontal. */
    @Column(name = "screen_type", length = 32)
    private String screenType;

    /** Human-readable project/script title. */
    @Column(nullable = false, length = 240)
    private String title;

    /** Plain text summary of the script, useful for exports and search. */
    @Column(name = "script_text", columnDefinition = "text")
    private String scriptText;

    /** Full cinematic planning JSON. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "script_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> scriptPayload = new LinkedHashMap<>();

    /** Shortcut shot array for storyboard rendering. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> shots = new ArrayList<>();

    /** Script lifecycle state such as GENERATED, LOCKED, or ARCHIVED. */
    @Column(nullable = false, length = 32)
    private String status;

    /** Timestamp when script row was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when script row was last updated. */
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
