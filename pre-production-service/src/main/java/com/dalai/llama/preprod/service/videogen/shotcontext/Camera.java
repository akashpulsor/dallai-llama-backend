package com.dalai.llama.preprod.service.videogen.shotcontext;

import com.dalai.llama.preprod.domain.ShotSize;

/**
 * Full cinematography taxonomy -- {@code shotSize}/{@code cameraNote} are the original v1 fields;
 * everything else is the camera/position/lens/composition/focus/movement/support/exposure/
 * temporal/filtration/image-character breakdown a real DP critic needs to reason about
 * contradictions (see critic-service's {@code CRITIC_DP_REVIEW} prompt). All nullable -- "not
 * specified by this shot", never absence-of-meaning; free-text rather than numeric because these
 * feed a generation/critique prompt, not a photographic calculation.
 */
public record Camera(
        ShotSize shotSize,
        String cameraNote,

        // -- camera body --
        String cameraBody,
        String sensor,
        String captureFormat,
        String recordingCharacteristics,

        // -- position --
        String positionHeight,
        String positionDistance,
        String positionLateral,
        String positionElevation,
        String positionOrientation,

        // -- lens --
        String lensFocalLength,
        String lensType,
        String lensOpticalFormat,
        String lensDistortion,
        String lensCompression,
        String lensCharacter,

        // -- composition --
        String framing,
        String subjectPlacement,
        String headroom,
        String leadRoom,
        String visualBalance,

        // -- focus --
        String focusTarget,
        String focusDistance,
        String depthOfField,
        String rackFocus,
        String focusBehaviour,

        // -- movement --
        String movementType,
        String movementTrajectory,
        String movementSpeed,
        String movementAcceleration,
        String movementRotation,
        String movementSubjectRelationship,

        // -- support --
        String support,

        // -- exposure --
        String aperture,
        String iso,
        String shutter,
        String ndFilter,
        String dynamicRange,

        // -- temporal --
        String shutterAngle,
        String motionBlur,
        String slowMotion,

        // -- filtration --
        String filtrationDiffusion,
        String filtrationNd,
        String filtrationPolarizer,
        String filtrationSpecialty,

        // -- image character --
        String contrast,
        String colorResponse,
        String grain,
        String halation,
        String bloom,
        String sharpness,
        String flare
) {
}
