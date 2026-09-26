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
        List<ShotReferenceImage> referenceImages
) {
    /** Pre-referenceImages arity kept for any legacy caller (there's currently only the
     * assembly service, but this keeps the record additive per the outbound-wire-contract
     * discipline this file uses). */
    public ShotContext(String shotRef, Narrative narrative, List<Character> characters,
                       Environment environment, Lighting lighting, Camera camera,
                       ProductBrand productBrand, Technical technical,
                       List<ContinuityAnchor> continuityAnchors, AudioAmbience audioAmbience,
                       List<DialogueBeat> dialogueBeats) {
        this(shotRef, narrative, characters, environment, lighting, camera, productBrand,
                technical, continuityAnchors, audioAmbience, dialogueBeats, null);
    }
}
