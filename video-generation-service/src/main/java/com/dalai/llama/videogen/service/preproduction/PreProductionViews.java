package com.dalai.llama.videogen.service.preproduction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire-mirror DTOs for pre-production-service's internal /api/v1/internal/** responses --
 * structural copies of {@code ContinuityBibleView}, {@code CastAssignmentView} etc. from that
 * service. No shared module between the two services (deliberate -- same "structural copy, no
 * shared module" convention {@code ShotContext} already uses), Jackson matches by field name.
 * Every field mirrors the pre-prod side's real DTO exactly; adding a field there requires
 * adding it here to consume it, and vice versa is silently ignored.
 */
public final class PreProductionViews {

    private PreProductionViews() {}

    public record ContinuityBibleView(String negativePrompt, List<ContinuityLockView> locks) {
        public record ContinuityLockView(String category, String value) {}
    }

    public record ProjectConfigView(
            String aspectRatio,
            Integer targetDurationSeconds,
            Boolean preferMotionGraphics,
            String preferredVideoModel,
            String preferredVoiceModel,
            String preferredLipSyncModel,
            String preferredTtsModel,
            String dialogueLanguage
    ) {}

    public record CastAssignmentView(
            UUID id,
            UUID scriptCharacterId,
            UUID castProfileId,
            String wardrobeNote,
            String performanceDirection
    ) {}

    public record CastProfileView(
            UUID id,
            UUID projectId,
            String profileType,
            String displayName,
            String faceRefBucket,
            String faceRefObjectKey,
            String faceRefUrl,
            String description,
            Integer age,
            String gender,
            String voiceRefBucket,
            String voiceRefObjectKey,
            String builtinVoiceId,
            String clonedVoiceId,
            String clonedVoiceProviderId,
            String voiceIdentityType,
            long projectCount
    ) {}

    /** Request mirror for the internal set-if-absent cloned voice endpoint. */
    public record PersistClonedVoiceRequest(String clonedVoiceId, String providerId, String voiceIdentityType) {}

    /** Effective clone identity returned by pre-production-service after the conditional write. */
    public record ClonedVoiceIdentityView(
            String clonedVoiceId,
            String providerId,
            String voiceIdentityType,
            boolean newlyPersisted
    ) {}

    /** Slim mirror of pre-prod's ShotView -- only the fields the prepare-scene assembler actually
     * uses (id/shotRef/shotType/status/durationSeconds/shot metadata). Additional fields on
     * pre-prod's ShotView are silently ignored by Jackson, which is what we want -- future
     * additions there won't break this consumer. */
    public record ShotView(
            UUID id,
            String shotRef,
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
            String status,
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
            // Everything below was already on the wire from pre-production-service's ShotView and
            // was being dropped here on deserialize -- this record declared 28 of its 50 fields,
            // so Jackson silently discarded the rest and the shot plan never reached a prompt.
            String creatorDirection,
            String subtitlePosition,
            String mobileFocusArea,
            String safeZoneNotes,
            String executionDifficulty,
            String cinematicExecution,
            String sketchPrompt,
            String coverageType,
            String screenDirection,
            Integer peopleInFrame,
            String culturalReferences,
            String productShotType,
            String directorNote,
            CinematographyView cinematography,
            ShotCastView cast
    ) {}

    /** The shot's full cinematography spec (pre-prod stores these as flat {@code cine_*} columns).
     * Mirrors pre-production-service's CinematographyView field-for-field. */
    public record CinematographyView(
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
    ) {}

    /** Who is actually in THIS shot, already resolved by pre-production from the shot's
     * primaryCharacterKey through ScriptCharacter to the cast assignment. Null for a shot with no
     * primary character or a NARRATOR (never in frame). */
    public record ShotCastView(
            String characterKey,
            String characterName,
            String characterType,
            UUID castProfileId,
            String castDisplayName,
            String castFaceImageUrl,
            boolean hasVoiceSample
    ) {}

    public record ShotDialogueBeatView(
            UUID id,
            Integer orderIndex,
            java.math.BigDecimal startSeconds,
            java.math.BigDecimal durationSeconds,
            String text,
            String characterKey,
            String clonedVoiceId
    ) {}

    public record CameraPlanView(
            UUID id,
            UUID shotId,
            String blockingMap,
            String executionSteps,
            Boolean gimbalEnabled,
            String gimbalDevice,
            String gimbalMode,
            String gimbalPanSpeed,
            String gimbalTiltSpeed,
            String safetyFlags,
            Boolean requiresCoordinator,
            String complianceNote,
            String source,
            String critiqueNotes
    ) {}

    public record LightingPlanView(
            UUID id,
            UUID shotId,
            String cinematicIntent,
            Integer estimatedSetupMinutes,
            String keyLightGear,
            String fillLightGear,
            String rimLightGear,
            String negFillGear,
            String diffuserGear,
            String cameraRigGear,
            String buildSteps,
            String source,
            String critiqueNotes
    ) {}

    public record ShotImageView(
            UUID id,
            String kind,
            String bucket,
            String objectKey,
            String signedUrl,
            OffsetDateTime createdAt
    ) {}

    public record ShotBackgroundMusicView(
            UUID id,
            String signedUrl,
            String prompt,
            OffsetDateTime createdAt
    ) {}

    /** Slim mirror of pre-prod's ScriptView -- only the fields the dialogue-beat cast resolver
     * uses. Additional fields on pre-prod's ScriptView are silently ignored by Jackson. */
    /** Mirrors pre-production-service's ScriptView. The narrative fields (hook, beat plan, arc,
     * logline...) were on the wire all along -- this record declared only id/projectId/characters,
     * so the story the shots are meant to tell never reached the prompt. beatPlan in particular
     * was persisted by V43 specifically so it would stop being thrown away. */
    public record ScriptView(
            UUID id,
            UUID projectId,
            String pacingStyle,
            String emotionalArc,
            String hookStrategy,
            String logline,
            String centralConflict,
            String endingPayoff,
            String setting,
            String hook,
            String beatPlan,
            String storytellingType,
            List<ScriptCharacterView> characters
    ) {}

    public record ScriptCharacterView(
            UUID id,
            String characterKey,
            String characterName,
            String characterType
    ) {}

    public record ShotProductReferenceView(
            UUID id,
            UUID shotId,
            String classification,
            String bucket,
            String objectKey,
            String signedUrl,
            String personDescription,
            String detectedSubject,
            String dominantMood,
            String referenceCameraAngle,
            String referenceLightingStyle,
            String referenceMotion,
            Boolean ignoreSubject
    ) {}

    /** Fat aggregate returned by pre-prod's {@code /prepare-bundle} endpoint. One HTTP call
     * carries continuity/config/script/cast at project scope plus every shot with all its
     * downstream rows nested, so a project prepare stops fanning out ~90 sequential requests
     * to pre-prod. Every nested field mirrors {@link PrepareBundleView} on the pre-prod side. */
    public record PrepareBundleView(
            ContinuityBibleView continuityBible,
            ProjectConfigView projectConfig,
            ScriptView script,
            List<CastAssignmentView> castAssignments,
            List<CastProfileView> castProfiles,
            List<ShotBundleView> shots
    ) {}

    public record ShotBundleView(
            ShotView shot,
            List<ShotDialogueBeatView> dialogueBeats,
            CameraPlanView cameraPlan,
            LightingPlanView lightingPlan,
            List<ShotImageView> shotImages,
            ShotBackgroundMusicView backgroundMusic,
            ShotProductReferenceView productReference
    ) {}
}
