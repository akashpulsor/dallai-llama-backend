package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.ScenePreparationStatus;
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

/**
 * Stage-1 output of the prepare-scene flow: one row per project holding the composed
 * project-scope prompt template (product/brand context, continuity bible, dialogue-language
 * defaults, editing-plan overview) -- the constant material every shot in the project
 * inherits. Stage 2 ({@code ShotContextAssemblyService}) reads this row alongside per-shot
 * pulls from pre-production-service; each per-shot {@code ShotPrompt} carries the
 * user-visible/editable prompt, this row is the shared preamble.
 *
 * <p>Idempotent: re-preparing overwrites in place -- this is the inputs snapshot, not a
 * versioned history. Per-shot prompt versioning lives on {@code ShotPrompt.parentPromptId}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project_scene_preparation")
public class ProjectScenePreparation {

    @Id
    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "template_text", nullable = false, columnDefinition = "text")
    private String templateText;

    @Column(name = "prepared_at", nullable = false)
    private OffsetDateTime preparedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Bulk shot-prepare lifecycle -- see {@link ScenePreparationStatus}. Defaults to READY for
     * a plain Stage-1 template prepare (no bulk loop has run yet, so treating the row as "ready"
     * to be used matches its observable state). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ScenePreparationStatus status;
}
