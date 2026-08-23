package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Enum-shaped fields are parsed as raw strings here and tolerantly mapped onto the real domain
 * enums in {@code ShotListGenerationService} (falling back to a sane default rather than failing
 * the whole batch on one malformed LLM value) -- the persisted {@link com.dalai.llama.preprod.domain.entity.Shot}
 * stays strictly typed either way. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShotListGenerationResult(
        List<ShotItem> shots
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShotItem(
            Integer sceneNumber,
            Integer shotNumber,
            String shotType,
            String scriptLine,
            String primaryCharacterKey,
            String cameraShotSize,
            String cameraNote,
            String location,
            String timeOfDay,
            String lightingMood,
            Integer durationSeconds,
            String aspectRatio,
            String cameraAngle,
            String cameraMovement,
            String lensSuggestion,
            Integer fps,
            String composition,
            String expression,
            String emotion,
            String bodyLanguage,
            String action,
            String voiceOver,
            String textOverlay,
            String soundDesign,
            String editingNotes,
            String retentionGoal,
            String creatorDirection,
            String subtitlePosition,
            String mobileFocusArea,
            String safeZoneNotes,
            String executionDifficulty,
            String cinematicExecution,
            String rookieFriendlyGuide,
            String sketchPrompt,
            String coverageType,
            String screenDirection,
            Integer peopleInFrame,
            String culturalReferences,
            String productShotType,
            String shootDay,
            String shootBlock,
            String directorNote,
            CinematographyItem cinematography
    ) {
    }

    /** Full cinematography taxonomy -- nested here rather than flattened onto {@link ShotItem} so
     * the LLM response shape stays legible (matches {@link com.dalai.llama.preprod.dto.CinematographyView}
     * field-for-field; all free text, nothing here is a closed vocabulary). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CinematographyItem(
            String cameraBody,
            String sensor,
            String captureFormat,
            String recordingCharacteristics,
            String positionHeight,
            String positionDistance,
            String positionLateral,
            String positionElevation,
            String positionOrientation,
            String lensFocalLength,
            String lensType,
            String lensOpticalFormat,
            String lensDistortion,
            String lensCompression,
            String lensCharacter,
            String framing,
            String subjectPlacement,
            String headroom,
            String leadRoom,
            String visualBalance,
            String focusTarget,
            String focusDistance,
            String depthOfField,
            String rackFocus,
            String focusBehaviour,
            String movementType,
            String movementTrajectory,
            String movementSpeed,
            String movementAcceleration,
            String movementRotation,
            String movementSubjectRelationship,
            String support,
            String aperture,
            String iso,
            String shutter,
            String ndFilter,
            String dynamicRange,
            String shutterAngle,
            String motionBlur,
            String slowMotion,
            String filtrationDiffusion,
            String filtrationNd,
            String filtrationPolarizer,
            String filtrationSpecialty,
            String contrast,
            String colorResponse,
            String grain,
            String halation,
            String bloom,
            String sharpness,
            String flare
    ) {
    }
}
