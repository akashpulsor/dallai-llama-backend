package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.AspectRatio;
import com.dalai.llama.preprod.domain.ExecutionDifficulty;
import com.dalai.llama.preprod.domain.MoodProfile;
import com.dalai.llama.preprod.domain.ShotSize;
import com.dalai.llama.preprod.domain.ShotStatus;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.TimeOfDay;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot")
public class Shot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "screenplay_scene_id", nullable = false)
    private UUID screenplaySceneId;

    /** Same soft-reference convention as {@code Script.lockedIdeaId} -- stamped from
     * {@code project.lockedIdeaId} at generation time. */
    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

    /** Stable identity used everywhere a shot is referenced across service boundaries (matches
     * {@code ShotContext.shotRef} on video-generation-service's side), not the surrogate id. */
    @Column(name = "shot_ref", nullable = false, length = 64)
    private String shotRef;

    @Column(name = "shot_number", nullable = false)
    private Integer shotNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "shot_type", nullable = false, length = 16)
    private ShotType shotType;

    @Column(name = "script_line")
    private String scriptLine;

    @Column(name = "primary_character_key", length = 160)
    private String primaryCharacterKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "camera_shot_size", length = 16)
    private ShotSize cameraShotSize;

    @Column(name = "camera_note")
    private String cameraNote;

    @Column(name = "location")
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_of_day", length = 16)
    private TimeOfDay timeOfDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "lighting_mood", length = 16)
    private MoodProfile lightingMood;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "aspect_ratio", length = 16)
    private AspectRatio aspectRatio;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ShotStatus status;

    // --- Detailed shot-planning fields, parity with creator-service's real
    // creator_storyboard_scenes richness (that service used raw jsonb maps for these; here they're
    // strictly typed strings/enums per this build's own no-maps rule). All nullable -- "not
    // populated yet", never absence-of-meaning. ---

    @Column(name = "camera_angle", length = 160)
    private String cameraAngle;

    @Column(name = "camera_movement", length = 160)
    private String cameraMovement;

    @Column(name = "lens_suggestion", length = 160)
    private String lensSuggestion;

    @Column(name = "fps")
    private Integer fps;

    @Column(name = "composition", columnDefinition = "text")
    private String composition;

    @Column(name = "expression", columnDefinition = "text")
    private String expression;

    @Column(name = "emotion", length = 240)
    private String emotion;

    @Column(name = "body_language", columnDefinition = "text")
    private String bodyLanguage;

    @Column(name = "action", columnDefinition = "text")
    private String action;

    @Column(name = "voice_over", columnDefinition = "text")
    private String voiceOver;

    @Column(name = "text_overlay", columnDefinition = "text")
    private String textOverlay;

    @Column(name = "sound_design", columnDefinition = "text")
    private String soundDesign;

    @Column(name = "editing_notes", columnDefinition = "text")
    private String editingNotes;

    @Column(name = "retention_goal", columnDefinition = "text")
    private String retentionGoal;

    @Column(name = "creator_direction", columnDefinition = "text")
    private String creatorDirection;

    @Column(name = "subtitle_position", length = 80)
    private String subtitlePosition;

    @Column(name = "mobile_focus_area", length = 120)
    private String mobileFocusArea;

    @Column(name = "safe_zone_notes", columnDefinition = "text")
    private String safeZoneNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_difficulty", length = 16)
    private ExecutionDifficulty executionDifficulty;

    @Column(name = "cinematic_execution", columnDefinition = "text")
    private String cinematicExecution;

    @Column(name = "rookie_friendly_guide", columnDefinition = "text")
    private String rookieFriendlyGuide;

    /** Feeds storyboard image generation once that capability exists in llm-gateway (a new
     * image-typed model, not yet built -- see the design doc follow-up). Captured now so the
     * shot-list generation call doesn't need to be re-run once it does. */
    @Column(name = "sketch_prompt", columnDefinition = "text")
    private String sketchPrompt;

    // --- Full cinematography taxonomy (camera/position/lens/composition/focus/movement/support/
    // exposure/temporal/filtration/image-character) -- mirrors ShotContext.Camera's expanded
    // fields exactly, since this is where the DP critic's reasoning material comes from. ---

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

    // --- Shot-plan richness restored from creator-service's real StoryboardTag (coverage/
    // continuity/production-logistics fields that had no equivalent here at all). ---

    @Column(name = "coverage_type", length = 80)
    private String coverageType;

    @Column(name = "screen_direction", length = 80)
    private String screenDirection;

    @Column(name = "people_in_frame")
    private Integer peopleInFrame;

    @Column(name = "cultural_references", columnDefinition = "text")
    private String culturalReferences;

    /** Marketing sub-category for a PRODUCT_HERO shot, e.g. "Hero Shot"/"Ingredient Shot"/
     * "Pack Shot" -- free text, not an enum, since the real vocabulary is category-dependent. */
    @Column(name = "product_shot_type", length = 80)
    private String productShotType;

    @Column(name = "shoot_day", length = 40)
    private String shootDay;

    @Column(name = "shoot_block", length = 40)
    private String shootBlock;

    @Column(name = "director_note", columnDefinition = "text")
    private String directorNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
