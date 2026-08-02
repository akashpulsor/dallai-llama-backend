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
import org.hibernate.annotations.Mutability;
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
@Table(name = "creator_script_shot_plans")
public class CreatorScriptShotPlan {

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

    @Column(name = "generation_job_id")
    private UUID generationJobId;

    @Column(name = "shot_number", nullable = false)
    private Integer shotNumber;

    @Column(name = "style_key", nullable = false, length = 80)
    private String styleKey;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "storyboard_tag", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> storyboardTag = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "lighting_build_sheet_tag", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> lightingBuildSheetTag = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "camera_plan_sheet_tag", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> cameraPlanSheetTag = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "prompt_run_ids", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> promptRunIds = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "input_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputPayload = new LinkedHashMap<>();

    @Column(nullable = false, length = 32)
    private String status;

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
        if (styleKey == null || styleKey.isBlank()) {
            styleKey = "indian_creator_pencil";
        }
        if (storyboardTag == null) {
            storyboardTag = new LinkedHashMap<>();
        }
        if (lightingBuildSheetTag == null) {
            lightingBuildSheetTag = new LinkedHashMap<>();
        }
        if (cameraPlanSheetTag == null) {
            cameraPlanSheetTag = new LinkedHashMap<>();
        }
        if (promptRunIds == null) {
            promptRunIds = new LinkedHashMap<>();
        }
        if (inputPayload == null) {
            inputPayload = new LinkedHashMap<>();
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
