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

    /** Mirrors pre-production's MotionGraphicPlanView. Only MOTION_GRAPHIC shots have one. */
    public record MotionGraphicPlanView(
            UUID id, UUID shotId, String concept, String onScreenText, String visualStyle,
            String animationNotes, Integer durationSeconds) {}

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
            String dialogueLanguage,
            /** The language the NARRATIVE is written in, which is not always the language it is
             * spoken in -- dialogueLanguage covers the spoken line only. */
            String narrativeLanguage,
            /** 480p/720p. The shot's own override wins; this is the project default, and without
             * it here a project-wide resolution choice never reached the job. */
            String preferredResolution,
            Boolean recommenderEnabled,
            Boolean costPreviewEnabled,
            Boolean priceDeltaModalEnabled,
            Boolean autoCloneAudioPromptEnabled
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
            /** The idea this project was locked to. Carried so a shot can be traced back to it
             * without a second call; not prompt content. */
            UUID lockedIdeaId,
            /** Which screenplay scene this shot belongs to. The one genuinely useful omission of
             * the five that were missing: without it nothing here can group shots by scene, which
             * is how scene-level context would ever be assembled. */
            UUID screenplaySceneId,
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
            /** Dub this shot with this provider voice instead of the one its cast resolves to.
             * Null is the normal state and means the cast decides. */
            String dubVoiceId,
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
            /** How a human crew would shoot this. Production guidance rather than prompt content,
             * mirrored so the record is a complete copy of what pre-production sends. */
            String rookieFriendlyGuide,
            String sketchPrompt,
            String coverageType,
            String screenDirection,
            Integer peopleInFrame,
            String culturalReferences,
            String productShotType,
            /** Scheduling for a live shoot. Mirrored for completeness; nothing here reads them. */
            String shootDay,
            String shootBlock,
            String directorNote,
            CinematographyView cinematography,
            ShotCastView cast,
            /** Inherited from the parent screenplay scene. True means the creator uploaded a
             * labelled bundle of real reference images for this beat (see referenceImages on
             * ShotBundleView) -- the prompt must USE them rather than inventing UI/packaging. */
            Boolean needsMultiImage,
            /** Creator's name for that bundle ("product flow", "before/after"). Null when
             * needsMultiImage is false. */
            String multiImageLabel,
            /** IDENTITY / MOTION_GRAPHIC / LIVE_ACTION / PRODUCT_HERO / GENERIC, inherited from
             * the scene. Wire-string, no shared enum. Null reads as GENERIC. */
            String sceneType
    ) {
    }

    /** One creator-uploaded reference image in a shot's multi-image bundle (pre-production V66
     * shot_reference_image). Mirrors pre-prod's ShotReferenceImageView. Caption is carried but
     * deliberately NOT surfaced as prompt text -- the prompt names the bundle by its label. */
    public record ShotReferenceImageView(
            UUID id,
            UUID shotId,
            String bucket,
            String objectKey,
            String contentType,
            String caption,
            String tag,
            Integer ordinal,
            /** Presigned by pre-production. This service reads bucket/objectKey and signs its own,
             * so nothing depends on it -- mirrored so the record is a complete copy. */
            String signedUrl,
            OffsetDateTime createdAt
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
            /** Words rendered INTO this frame, and the script they are written in. The frame is
             * handed to the video model as a reference, so the prompt has to know what it already
             * says -- otherwise the model is free to invent different words over the top, or to
             * romanise Devanagari that the film deliberately set in its own script. */
            String onScreenText,
            String onScreenTextLanguage,
            OffsetDateTime createdAt
    ) {}

    /** Mirrors pre-production-service's ShotFoleyCueView. */
    public record ShotFoleyCueView(Integer timestampMs, String cueType, String description) {}

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
            UUID lockedIdeaId,
            /** The script itself. Everything else here is metadata ABOUT it; the prose was the one
             * thing not carried across. */
            String scriptText,
            /** The film has no people in it. A real creative constraint -- a prompt that invents a
             * presenter for a product film breaks the brief -- and it could not reach the prompt
             * while this was dropped on deserialize. */
            Boolean noHumans,
            String status,
            Integer currentVersion,
            String currentSource,
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
            ShotProductReferenceView productReference,
            /** Derived once by pre-production when the shot was planned. */
            List<ShotFoleyCueView> foleyCues,
            /** Only MOTION_GRAPHIC shots have one; null for every other shot type. */
            MotionGraphicPlanView motionGraphicPlan,
            /** Creator-uploaded multi-image bundle for this shot, ordered. Empty unless the shot's
             * scene was flagged needsMultiImage. These reach the composed prompt (named by
             * ShotView.multiImageLabel) and ride in reference_image_urls at dispatch. */
            List<ShotReferenceImageView> referenceImages
    ) {

        /** Pre-referenceImages arity. */
        public ShotBundleView(ShotView shot, List<ShotDialogueBeatView> dialogueBeats, CameraPlanView cameraPlan,
                              LightingPlanView lightingPlan, List<ShotImageView> shotImages,
                              ShotBackgroundMusicView backgroundMusic, ShotProductReferenceView productReference,
                              List<ShotFoleyCueView> foleyCues, MotionGraphicPlanView motionGraphicPlan) {
            this(shot, dialogueBeats, cameraPlan, lightingPlan, shotImages, backgroundMusic, productReference,
                    foleyCues, motionGraphicPlan, List.of());
        }

        /** Pre-foleyCues arity, for callers and tests that build a bundle by hand. */
        public ShotBundleView(ShotView shot, List<ShotDialogueBeatView> dialogueBeats, CameraPlanView cameraPlan,
                              LightingPlanView lightingPlan, List<ShotImageView> shotImages,
                              ShotBackgroundMusicView backgroundMusic, ShotProductReferenceView productReference) {
            this(shot, dialogueBeats, cameraPlan, lightingPlan, shotImages, backgroundMusic, productReference,
                    List.of(), null);
        }

        /** Pre-motionGraphicPlan arity, same purpose. */
        public ShotBundleView(ShotView shot, List<ShotDialogueBeatView> dialogueBeats, CameraPlanView cameraPlan,
                              LightingPlanView lightingPlan, List<ShotImageView> shotImages,
                              ShotBackgroundMusicView backgroundMusic, ShotProductReferenceView productReference,
                              List<ShotFoleyCueView> foleyCues) {
            this(shot, dialogueBeats, cameraPlan, lightingPlan, shotImages, backgroundMusic, productReference,
                    foleyCues, null);
        }
    }
}
