package com.dalai.llama.preprod.service.videogen.shotcontext;

import java.util.List;

/** Outbound wire-contract copy of video-generation-service's real {@code ShotContext} --
 * assembled by {@code ShotContextAssemblyService}, never authored by hand elsewhere. */
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
        /** Creator-uploaded multi-image reference bundle for this shot (V66
         * shot_reference_image). Assembled from ShotReferenceImageRepository. Empty when the
         * shot has no bundle. Flows through video-gen's SHOT_REFERENCE saveReferences path so
         * the model sees these images in reference_image_urls at dispatch time. */
        List<ShotReferenceImage> referenceImages,
        /** Bundle label -- from shot.multiImageLabel. Sent alongside the images so llm-gateway's
         * prompt composer can name the bundle in the prompt (e.g. "app flow", "before/after")
         * without walking every image caption. */
        String referenceImagesLabel,
        /** Structural intent inherited from the parent screenplay scene (IDENTITY /
         * MOTION_GRAPHIC / LIVE_ACTION / PRODUCT_HERO / GENERIC). Wire-string so downstream
         * services don't need to share an enum class. Null flows through as "GENERIC" downstream. */
        String sceneType
) {
    /** Pre-referenceImages arity kept for any legacy caller. */
    public ShotContext(String shotRef, Narrative narrative, List<Character> characters,
                       Environment environment, Lighting lighting, Camera camera,
                       ProductBrand productBrand, Technical technical,
                       List<ContinuityAnchor> continuityAnchors, AudioAmbience audioAmbience,
                       List<DialogueBeat> dialogueBeats) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand,
                technical, continuityAnchors, audioAmbience, dialogueBeats, null, null, null);
    }

    /** Pre-referenceImagesLabel arity. */
    public ShotContext(String shotRef, Narrative narrative, List<Character> characters,
                       Environment environment, Lighting lighting, Camera camera,
                       ProductBrand productBrand, Technical technical,
                       List<ContinuityAnchor> continuityAnchors, AudioAmbience audioAmbience,
                       List<DialogueBeat> dialogueBeats, List<ShotReferenceImage> referenceImages) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand,
                technical, continuityAnchors, audioAmbience, dialogueBeats, referenceImages, null, null);
    }

    /** Pre-sceneType arity. */
    public ShotContext(String shotRef, Narrative narrative, List<Character> characters,
                       Environment environment, Lighting lighting, Camera camera,
                       ProductBrand productBrand, Technical technical,
                       List<ContinuityAnchor> continuityAnchors, AudioAmbience audioAmbience,
                       List<DialogueBeat> dialogueBeats, List<ShotReferenceImage> referenceImages,
                       String referenceImagesLabel) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand,
                technical, continuityAnchors, audioAmbience, dialogueBeats, referenceImages,
                referenceImagesLabel, null);
    }
}
