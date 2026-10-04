package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.FlagState;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project_config")
public class ProjectConfig {

    @Id
    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_dialogue_flag", nullable = false, length = 8)
    private FlagState defaultDialogueFlag;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_captions_flag", nullable = false, length = 8)
    private FlagState defaultCaptionsFlag;

    @Column(name = "auto_approve", nullable = false)
    private Boolean autoApprove;

    /** null uses {@code BeatDubbingService}'s own configured default
     * ({@code video-gen.llm-gateway.default-voice-clone-model}) -- a project-level pick from
     * llm-gateway's real {@code model_master} (type=voice_clone), same "master data, not a
     * hardcoded list" precedent {@code preferredVideoModel} already set on pre-production-
     * service's side. */
    @Column(name = "preferred_voice_clone_model")
    private String preferredVoiceCloneModel;

    // ---- Generation controls (V39). Each switches one step of the render path; the defaults are
    // the path that always lets a shot be generated. See GenerationControlsService.

    @Builder.Default
    @Column(name = "fit_duration_to_dialogue", nullable = false)
    private Boolean fitDurationToDialogue = false;

    @Builder.Default
    @Column(name = "auto_dub_dialogue", nullable = false)
    private Boolean autoDubDialogue = true;

    @Builder.Default
    @Column(name = "mix_background_music", nullable = false)
    private Boolean mixBackgroundMusic = true;

    @Builder.Default
    @Column(name = "prevent_duplicate_renders", nullable = false)
    private Boolean preventDuplicateRenders = true;

    @Builder.Default
    @Column(name = "attach_previous_last_frame", nullable = false)
    private Boolean attachPreviousLastFrame = false;

    @Builder.Default
    @Column(name = "conform_to_planned_duration", nullable = false)
    private Boolean conformToPlannedDuration = true;

    @Builder.Default
    @Column(name = "interpolate_when_slowing", nullable = false)
    private Boolean interpolateWhenSlowing = true;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
