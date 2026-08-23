package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.AspectRatio;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * Explicit, typed provider preferences carried from the locked idea's {@code budget_tier} --
 * deliberately not a jsonb bag, so a new preference is a migration + field, not a silent key
 * only some callers know about.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project_config")
public class ProjectConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    @Column(name = "preferred_video_model")
    private String preferredVideoModel;

    @Column(name = "preferred_voice_model")
    private String preferredVoiceModel;

    @Column(name = "preferred_lip_sync_model")
    private String preferredLipSyncModel;

    /** Set once, before/alongside script generation -- what every stage after it (shot-list
     * aspect ratio, video generation) plans around unless a shot has a deliberate reason to
     * differ. Null until the creator sets it (the project-settings panel defaults to RATIO_9_16
     * client-side, not written here until saved). */
    @Enumerated(EnumType.STRING)
    @Column(name = "aspect_ratio", length = 16)
    private AspectRatio aspectRatio;

    @Column(name = "target_duration_seconds")
    private Integer targetDurationSeconds;

    /** BCP-47 code (e.g. 'hi-IN') from llm-gateway's language_master -- the dialogue language every
     * later stage (script generation, dialogue-sync/voice-cloning) plans around. Null until the
     * creator sets it; script generation falls back to a default rather than failing. */
    @Column(name = "dialogue_language", length = 16)
    private String dialogueLanguage;

    /** When true, shot-list generation is told to prefer MOTION_GRAPHIC for text/data/graphic-
     * driven beats instead of leaving that judgment entirely to the model's own read of each
     * beat. */
    @Column(name = "prefer_motion_graphics", nullable = false)
    @Builder.Default
    private Boolean preferMotionGraphics = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
