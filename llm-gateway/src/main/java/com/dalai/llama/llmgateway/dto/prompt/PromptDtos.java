package com.dalai.llama.llmgateway.dto.prompt;

import java.math.BigDecimal;
import java.util.List;

/**
 * All DTOs consumed by {@code /v1/prompt/format} -- ShotContext + nested cinematography types
 * + feature flags + response shape. Consolidated into one file so a caller (video-gen) can
 * import a single class and pattern-mirrors {@code PreProductionViews}. String-typed enum
 * fields on purpose: llm-gateway shouldn't own video-gen's cinematography enum tree, and the
 * strategies only read String content anyway.
 */
public final class PromptDtos {

    private PromptDtos() {}

    /** Incoming request body for {@code POST /v1/prompt/format}. */
    public record PromptFormatRequest(String modelId, ShotContext shotContext, FeatureFlags flags) {}

    /** Response of {@code POST /v1/prompt/format} -- the composed positive/negative prompt plus
     * the resolved strategy's max prompt length so a caller's compression stage can decide
     * whether to compress and how far without a second round-trip. */
    public record PromptFormatResponse(String positive, String negative, int maxPromptLength) {}

    public record ShotContext(
            String shotRef,
            Narrative narrative,
            List<Character> characters,
            Environment environment,
            Lighting lighting,
            Camera camera,
            ProductBrand productBrand,
            Technical technical,
            List<ContinuityAnchor> continuityAnchors,
            AudioAmbience audioAmbience,
            List<DialogueBeat> dialogueBeats,
            /** Creator-uploaded multi-image reference bundle -- V66 on pre-production-service.
             * Present when the shot's scene was flagged needsMultiImage. The composed prompt
             * mentions the bundle by its label (never per-image captions), so the model knows
             * the trailing reference_image_urls carry a labeled set. The label
             * (multiImageLabel) is a structural tag like "app flow" or "before/after". */
            List<ShotReferenceImage> referenceImages,
            /** Bundle label -- carried alongside referenceImages so the prompt can name the set
             * without walking every image. Null when no bundle exists. */
            String referenceImagesLabel,
            /** Structural scene-type tag from pre-prod (IDENTITY / MOTION_GRAPHIC / LIVE_ACTION /
             * PRODUCT_HERO / GENERIC). Read as a plain string, no shared enum. Null or unknown
             * flows through as GENERIC in DefaultPromptStrategy. */
            String sceneType
    ) {
        /** Backward-compat arity for callers that don't send reference-image fields yet. */
        public ShotContext(String shotRef, Narrative narrative, List<Character> characters,
                           Environment environment, Lighting lighting, Camera camera,
                           ProductBrand productBrand, Technical technical,
                           List<ContinuityAnchor> continuityAnchors, AudioAmbience audioAmbience,
                           List<DialogueBeat> dialogueBeats) {
            this(shotRef, narrative, characters, environment, lighting, camera, productBrand,
                    technical, continuityAnchors, audioAmbience, dialogueBeats, null, null, null);
        }

        /** Pre-sceneType arity -- for callers that already send the reference-image bundle but
         * not sceneType yet. */
        public ShotContext(String shotRef, Narrative narrative, List<Character> characters,
                           Environment environment, Lighting lighting, Camera camera,
                           ProductBrand productBrand, Technical technical,
                           List<ContinuityAnchor> continuityAnchors, AudioAmbience audioAmbience,
                           List<DialogueBeat> dialogueBeats,
                           List<ShotReferenceImage> referenceImages, String referenceImagesLabel) {
            this(shotRef, narrative, characters, environment, lighting, camera, productBrand,
                    technical, continuityAnchors, audioAmbience, dialogueBeats,
                    referenceImages, referenceImagesLabel, null);
        }
    }

    /** One creator-uploaded reference image on a shot. Just the URL parts + ordinal are needed
     * for structural tagging in the prompt -- captions are deliberately NOT read here (they
     * live on the pre-prod row but the model prompt names the bundle, not each image). */
    public record ShotReferenceImage(
            String bucket,
            String objectKey,
            String contentType,
            String caption,
            Integer ordinal
    ) {}

    /** {@code dialogue} is what the character says; {@code scriptLine} is what happens in frame.
     * They used to be the same field read twice, so a shot's action and its spoken line came out
     * as the same sentence printed twice. */
    public record Narrative(String scriptLine, String screenplaySlug, String arcPosition, String dialogue) {}

    public record Character(
            String castId,
            String faceRefBucket,
            String faceRefObjectKey,
            String wardrobeNote,
            String performanceDirection,
            String voiceRefBucket,
            String voiceRefObjectKey
    ) {}

    public record Environment(String location, String timeOfDay, String weather, String environmentalEffects) {}

    public record Lighting(
            String keyLightNote,
            String mood,
            String dpLightingImageBucket,
            String dpLightingImageObjectKey
    ) {}

    /** Structural mirror of video-generation-service's Camera -- the full cinematography taxonomy.
     * It used to declare only the first four fields, so everything the shot plan said about body,
     * lens, focus, movement, exposure and look was dropped here on deserialize even when the
     * caller sent it. All nullable: "not specified by this shot". */
    public record Camera(
            String shotSize,
            String cameraNote,
            String cameraPlanImageBucket,
            String cameraPlanImageObjectKey,

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

    public record ProductBrand(
            Boolean isProductHeroShot,
            String productPlacement,
            String productRefBucket,
            String productRefObjectKey
    ) {}

    public record Technical(
            Integer durationSeconds,
            String aspectRatio,
            String targetProvider,
            String targetModel,
            String voiceCloneModel,
            String editingNotes,
            /** The resolution this shot will actually be rendered at -- the caller's own
             * argument, already resolved from the per-request override or the project config.
             * The caller has always sent it; this record simply did not declare it, so Jackson
             * dropped it and the prompt had no idea what it was being rendered at. */
            String resolution
    ) {}

    public record ContinuityAnchor(String anchorType, String subjectId, String description, String referenceObjectKey) {}

    public record AudioAmbience(
            String ambientDescription,
            String musicMoodNote,
            String backgroundMusicBucket,
            String backgroundMusicObjectKey
    ) {}

    public record DialogueBeat(
            BigDecimal startSeconds,
            BigDecimal durationSeconds,
            String text,
            String characterKey,
            String voiceReferenceUrl
    ) {}

    /** doc §19 -- closed vocabulary "ON"/"OFF" as strings so the wire format is stable across
     * services that don't share an enum. Strategies compare against {@link #ON} / {@link #OFF}. */
    public record FeatureFlags(String dialogue, String captions) {
        public static final String ON = "ON";
        public static final String OFF = "OFF";
    }
}
