package com.dalai.llama.preprod.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Restores creator-service's real CameraPlanSheetTag -- what feeds the CAMERA_PLAN {@link
 * com.dalai.llama.preprod.domain.ShotImageKind}'s prompt with structured content instead of just
 * the shot's flat camera fields. {@code gimbal*} fields are a typed subset of the old system's
 * fuller gimbal-settings shape (enabled/device/mode/pan+tilt speed) -- the fields that actually
 * change what the build sheet says, not every knob. One plan per shot; regenerating overwrites. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "camera_plan")
public class CameraPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false, unique = true)
    private UUID shotId;

    /** Top-down description of camera position, subject position, and movement path. */
    @Column(name = "blocking_map", columnDefinition = "text")
    private String blockingMap;

    /** Newline-joined ordered steps, same fixed sequence idea as the old system's SET MARKS ->
     * FRAME UP -> FOCUS PULL -> EXPOSURE -> REHEARSE MOVEMENT -> ROLL -> CHECK PLAYBACK. */
    @Column(name = "execution_steps", columnDefinition = "text")
    private String executionSteps;

    @Column(name = "gimbal_enabled", nullable = false)
    @Builder.Default
    private Boolean gimbalEnabled = false;

    @Column(name = "gimbal_device", columnDefinition = "text")
    private String gimbalDevice;

    @Column(name = "gimbal_mode", columnDefinition = "text")
    private String gimbalMode;

    @Column(name = "gimbal_pan_speed", columnDefinition = "text")
    private String gimbalPanSpeed;

    @Column(name = "gimbal_tilt_speed", columnDefinition = "text")
    private String gimbalTiltSpeed;

    @Column(name = "safety_flags", columnDefinition = "text")
    private String safetyFlags;

    @Column(name = "requires_coordinator", nullable = false)
    @Builder.Default
    private Boolean requiresCoordinator = false;

    @Column(name = "compliance_note", columnDefinition = "text")
    private String complianceNote;

    /** GENERATED, EDITED, or CRITIC -- same convention as Script's GenerationSource, just not a
     * shared enum (this plan has no version history to make a whole enum+FK worth it). */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** Set only when source=CRITIC -- the actual feedback that forced a revision. */
    @Column(name = "critique_notes", columnDefinition = "text")
    private String critiqueNotes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
