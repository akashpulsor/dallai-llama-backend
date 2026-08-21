package com.dalai.llama.critic.domain.entity;

import com.dalai.llama.critic.domain.AspectRatio;
import com.dalai.llama.critic.domain.EmotionalArcPosition;
import com.dalai.llama.critic.domain.MoodProfile;
import com.dalai.llama.critic.domain.PlanSnapshotKind;
import com.dalai.llama.critic.domain.ShotSize;
import com.dalai.llama.critic.domain.TimeOfDay;
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
 * A full, flattened snapshot of one {@code ShotContext} -- every single-valued field of
 * {@code ShotContext}'s nested objects (narrative/environment/lighting/camera/productBrand/
 * technical/audioAmbience) as its own typed column, no JSON anywhere. The two list-valued fields
 * ({@code characters}, {@code continuityAnchors}) live in their own child tables ({@link
 * CritiquePlanCharacter}, {@link CritiquePlanContinuityAnchor}) keyed by this snapshot's id.
 * One {@code CritiqueSession} has exactly one ORIGINAL snapshot, and a REVISED one only when the
 * revision planner ran.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "critique_plan_snapshot")
public class CritiquePlanSnapshot {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 8)
    private PlanSnapshotKind kind;

    @Column(name = "shot_ref", nullable = false, length = 64)
    private String shotRef;

    // --- narrative ---
    @Column(name = "narrative_script_line", columnDefinition = "text")
    private String narrativeScriptLine;

    @Column(name = "narrative_screenplay_slug", length = 220)
    private String narrativeScreenplaySlug;

    @Enumerated(EnumType.STRING)
    @Column(name = "narrative_arc_position", length = 16)
    private EmotionalArcPosition narrativeArcPosition;

    // --- environment ---
    @Column(name = "environment_location")
    private String environmentLocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment_time_of_day", length = 16)
    private TimeOfDay environmentTimeOfDay;

    @Column(name = "environment_weather")
    private String environmentWeather;

    @Column(name = "environment_effects", columnDefinition = "text")
    private String environmentEffects;

    // --- lighting ---
    @Column(name = "lighting_key_light_note", columnDefinition = "text")
    private String lightingKeyLightNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "lighting_mood", length = 16)
    private MoodProfile lightingMood;

    // --- camera ---
    @Enumerated(EnumType.STRING)
    @Column(name = "camera_shot_size", length = 16)
    private ShotSize cameraShotSize;

    @Column(name = "camera_note", columnDefinition = "text")
    private String cameraNote;

    // --- camera: full cinematography taxonomy (mirrors ShotContext.Camera's expanded fields) ---
    @Column(name = "cine_camera_body", length = 160)
    private String cineCameraBody;

    @Column(name = "cine_sensor", length = 160)
    private String cineSensor;

    @Column(name = "cine_capture_format", length = 160)
    private String cineCaptureFormat;

    @Column(name = "cine_recording_characteristics", columnDefinition = "text")
    private String cineRecordingCharacteristics;

    @Column(name = "cine_position_height", length = 160)
    private String cinePositionHeight;

    @Column(name = "cine_position_distance", length = 160)
    private String cinePositionDistance;

    @Column(name = "cine_position_lateral", length = 160)
    private String cinePositionLateral;

    @Column(name = "cine_position_elevation", length = 160)
    private String cinePositionElevation;

    @Column(name = "cine_position_orientation", length = 160)
    private String cinePositionOrientation;

    @Column(name = "cine_lens_focal_length", length = 80)
    private String cineLensFocalLength;

    @Column(name = "cine_lens_type", length = 80)
    private String cineLensType;

    @Column(name = "cine_lens_optical_format", length = 80)
    private String cineLensOpticalFormat;

    @Column(name = "cine_lens_distortion", length = 160)
    private String cineLensDistortion;

    @Column(name = "cine_lens_compression", length = 160)
    private String cineLensCompression;

    @Column(name = "cine_lens_character", columnDefinition = "text")
    private String cineLensCharacter;

    @Column(name = "cine_framing", columnDefinition = "text")
    private String cineFraming;

    @Column(name = "cine_subject_placement", length = 160)
    private String cineSubjectPlacement;

    @Column(name = "cine_headroom", length = 80)
    private String cineHeadroom;

    @Column(name = "cine_lead_room", length = 80)
    private String cineLeadRoom;

    @Column(name = "cine_visual_balance", columnDefinition = "text")
    private String cineVisualBalance;

    @Column(name = "cine_focus_target", length = 160)
    private String cineFocusTarget;

    @Column(name = "cine_focus_distance", length = 80)
    private String cineFocusDistance;

    @Column(name = "cine_depth_of_field", length = 80)
    private String cineDepthOfField;

    @Column(name = "cine_rack_focus", columnDefinition = "text")
    private String cineRackFocus;

    @Column(name = "cine_focus_behaviour", columnDefinition = "text")
    private String cineFocusBehaviour;

    @Column(name = "cine_movement_type", length = 160)
    private String cineMovementType;

    @Column(name = "cine_movement_trajectory", columnDefinition = "text")
    private String cineMovementTrajectory;

    @Column(name = "cine_movement_speed", length = 80)
    private String cineMovementSpeed;

    @Column(name = "cine_movement_acceleration", length = 80)
    private String cineMovementAcceleration;

    @Column(name = "cine_movement_rotation", length = 160)
    private String cineMovementRotation;

    @Column(name = "cine_movement_subject_relationship", columnDefinition = "text")
    private String cineMovementSubjectRelationship;

    @Column(name = "cine_support", length = 160)
    private String cineSupport;

    @Column(name = "cine_aperture", length = 80)
    private String cineAperture;

    @Column(name = "cine_iso", length = 80)
    private String cineIso;

    @Column(name = "cine_shutter", length = 80)
    private String cineShutter;

    @Column(name = "cine_nd_filter", length = 80)
    private String cineNdFilter;

    @Column(name = "cine_dynamic_range", length = 160)
    private String cineDynamicRange;

    @Column(name = "cine_shutter_angle", length = 80)
    private String cineShutterAngle;

    @Column(name = "cine_motion_blur", length = 160)
    private String cineMotionBlur;

    @Column(name = "cine_slow_motion", length = 160)
    private String cineSlowMotion;

    @Column(name = "cine_filtration_diffusion", length = 160)
    private String cineFiltrationDiffusion;

    @Column(name = "cine_filtration_nd", length = 80)
    private String cineFiltrationNd;

    @Column(name = "cine_filtration_polarizer", length = 80)
    private String cineFiltrationPolarizer;

    @Column(name = "cine_filtration_specialty", length = 160)
    private String cineFiltrationSpecialty;

    @Column(name = "cine_contrast", length = 160)
    private String cineContrast;

    @Column(name = "cine_color_response", length = 160)
    private String cineColorResponse;

    @Column(name = "cine_grain", length = 160)
    private String cineGrain;

    @Column(name = "cine_halation", length = 160)
    private String cineHalation;

    @Column(name = "cine_bloom", length = 160)
    private String cineBloom;

    @Column(name = "cine_sharpness", length = 160)
    private String cineSharpness;

    @Column(name = "cine_flare", length = 160)
    private String cineFlare;

    // --- productBrand ---
    @Column(name = "product_is_hero_shot")
    private Boolean productIsHeroShot;

    @Column(name = "product_placement", columnDefinition = "text")
    private String productPlacement;

    @Column(name = "product_ref_bucket")
    private String productRefBucket;

    @Column(name = "product_ref_object_key")
    private String productRefObjectKey;

    // --- technical ---
    @Column(name = "technical_duration_seconds")
    private Integer technicalDurationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "technical_aspect_ratio", length = 16)
    private AspectRatio technicalAspectRatio;

    @Column(name = "technical_target_provider")
    private String technicalTargetProvider;

    @Column(name = "technical_target_model")
    private String technicalTargetModel;

    // --- audioAmbience ---
    @Column(name = "audio_ambient_description", columnDefinition = "text")
    private String audioAmbientDescription;

    @Column(name = "audio_music_mood_note", columnDefinition = "text")
    private String audioMusicMoodNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
