package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.ShotSize;

/**
 * Full cinematography taxonomy -- {@code shotSize}/{@code cameraNote} are the original v1 fields
 * plus this service's camera-plan image pointer; everything else is the camera/position/lens/
 * composition/focus/movement/support/exposure/temporal/filtration/image-character breakdown.
 *
 * <p>These fields already existed on pre-production-service's matching {@code Camera} record and
 * were being sent on every request. This record declared four of them, so Jackson dropped the
 * other fifty at deserialize and the cinematography never reached a prompt. All nullable -- "not
 * specified by this shot", never absence-of-meaning; free text rather than numeric because these
 * feed a generation prompt, not a photographic calculation.
 */
public record Camera(
        ShotSize shotSize,
        String cameraNote,
        /** The CAMERA_PLAN-kind ShotImage (MinIO location) -- attached as a CAMERA_PLAN_IMAGE
         * reference. Local to this service; pre-production's copy has no equivalent. */
        String cameraPlanImageBucket,
        String cameraPlanImageObjectKey,

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

    /** Pre-cinematography arity, kept so existing callers and tests compile unchanged. */
    public Camera(ShotSize shotSize, String cameraNote, String cameraPlanImageBucket, String cameraPlanImageObjectKey) {
        this(shotSize, cameraNote, cameraPlanImageBucket, cameraPlanImageObjectKey,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
