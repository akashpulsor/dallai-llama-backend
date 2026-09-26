package com.dalai.llama.videogen.dto.shotcontext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Doc §2a Rules 1-7: strongly typed everywhere, no maps, nullable = "not populated by the
 * caller" (never absence-of-meaning). Field set is scoped to what {@code ProviderRequestBuilder}
 * in creator-service actually uses today, not the source doc's full 11-branch cinematography
 * tree -- new fields land the same way (additive, nullable) as real demand shows up.
 */
public record ShotContext(
        @NotBlank String shotRef,
        @Valid Narrative narrative,
        List<@Valid Character> characters,
        @Valid Environment environment,
        @Valid Lighting lighting,
        @Valid Camera camera,
        @Valid ProductBrand productBrand,
        @Valid Technical technical,
        List<@Valid ContinuityAnchor> continuityAnchors,
        @Valid AudioAmbience audioAmbience,
        List<@Valid DialogueBeat> dialogueBeats,
        /** The shot's own already-rendered frames (storyboard / production still / motion-graphic
         * preview). Additive and nullable per this record's contract above, so an older caller's
         * JSON body -- or the pre-hoc arity below -- still deserializes. */
        List<@Valid ReferenceFrame> referenceFrames,
        /** Set only on MOTION_GRAPHIC shots, and its presence is what identifies one. Null
         * everywhere else, so every other shot type composes its prompt exactly as before. */
        @Valid MotionGraphic motionGraphic,
        /** Creator-uploaded multi-image reference bundle for this shot (see pre-production
         * -service V66 -- shot_reference_image rows on a shot whose parent scene was flagged
         * needsMultiImage). Empty or null when the shot has no such bundle. These flow through
         * saveReferences as SHOT_REFERENCE-kind rows and end up in reference_image_urls at
         * dispatch, so the video model actually sees the images alongside the character-face /
         * product-hero / DP-lighting refs it already gets. */
        List<@Valid ShotReferenceImage> referenceImages,
        /** Bundle label -- shot.multiImageLabel from pre-prod. Passed through to llm-gateway
         * where the prompt strategy names the bundle in one structural line ("app flow",
         * "before/after") without per-image captions. */
        String referenceImagesLabel,
        /** Structural intent tag from pre-prod (IDENTITY / MOTION_GRAPHIC / LIVE_ACTION /
         * PRODUCT_HERO / GENERIC). Wire-string, no shared enum. Null flows through as GENERIC
         * downstream. */
        String sceneType
) {

    /** One uploaded reference image on a shot -- mirror of pre-prod-service's
     * ShotReferenceImageView. Caption is intentionally NOT surfaced in the prompt text (see
     * DefaultPromptStrategy for the structural bundle line). */
    public record ShotReferenceImage(
            String bucket,
            String objectKey,
            String contentType,
            String caption,
            Integer ordinal
    ) {}

    /** True when this shot is a motion graphic with something planned to animate. The single test
     * the prepare path branches on -- nothing else changes behaviour by shot type. */
    public boolean isPlannedMotionGraphic() {
        return motionGraphic != null && motionGraphic.hasPlan();
    }

    /** The same shot with a different narrative -- used when the dialogue has to be shortened to
     * fit the shot's duration, which replaces one field and must leave the rest untouched. */
    public ShotContext withNarrative(Narrative replacement) {
        return new ShotContext(shotRef, replacement, characters, environment, lighting, camera,
                productBrand, technical, continuityAnchors, audioAmbience, dialogueBeats, referenceFrames,
                motionGraphic, referenceImages, referenceImagesLabel, sceneType);
    }

    /** Pre-referenceFrames arity, kept so existing callers and tests compile unchanged. */
    public ShotContext(
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
            List<DialogueBeat> dialogueBeats
    ) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand, technical,
                continuityAnchors, audioAmbience, dialogueBeats, null, null, null, null);
    }

    /** Pre-motionGraphic arity, kept so existing callers and tests compile unchanged. */
    public ShotContext(
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
            List<ReferenceFrame> referenceFrames
    ) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand, technical,
                continuityAnchors, audioAmbience, dialogueBeats, referenceFrames, null, null, null);
    }

    /** Pre-referenceImages arity -- keeps every existing motionGraphic-aware caller compatible. */
    public ShotContext(
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
            List<ReferenceFrame> referenceFrames,
            MotionGraphic motionGraphic
    ) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand, technical,
                continuityAnchors, audioAmbience, dialogueBeats, referenceFrames, motionGraphic, null, null, null);
    }

    /** Pre-referenceImagesLabel arity. */
    public ShotContext(
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
            List<ReferenceFrame> referenceFrames,
            MotionGraphic motionGraphic,
            List<ShotReferenceImage> referenceImages
    ) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand, technical,
                continuityAnchors, audioAmbience, dialogueBeats, referenceFrames, motionGraphic, referenceImages, null, null);
    }

    /** Pre-sceneType arity. */
    public ShotContext(
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
            List<ReferenceFrame> referenceFrames,
            MotionGraphic motionGraphic,
            List<ShotReferenceImage> referenceImages,
            String referenceImagesLabel
    ) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand, technical,
                continuityAnchors, audioAmbience, dialogueBeats, referenceFrames, motionGraphic,
                referenceImages, referenceImagesLabel, null);
    }
}
