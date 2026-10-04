package com.dalai.llama.videogen.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One shot's own generation controls, replacing its project's defaults. See V41. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_generation_controls")
public class ShotGenerationControls {

    @Id
    @Column(name = "shot_id")
    private UUID shotId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "fit_duration_to_dialogue", nullable = false)
    private boolean fitDurationToDialogue;

    @Column(name = "auto_dub_dialogue", nullable = false)
    private boolean autoDubDialogue;

    @Column(name = "mix_background_music", nullable = false)
    private boolean mixBackgroundMusic;

    @Column(name = "prevent_duplicate_renders", nullable = false)
    private boolean preventDuplicateRenders;

    @Column(name = "attach_previous_last_frame", nullable = false)
    private boolean attachPreviousLastFrame;

    @Column(name = "conform_to_planned_duration", nullable = false)
    private boolean conformToPlannedDuration;

    @Column(name = "interpolate_when_slowing", nullable = false)
    private boolean interpolateWhenSlowing;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
