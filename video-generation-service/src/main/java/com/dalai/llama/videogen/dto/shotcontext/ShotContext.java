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
        List<@Valid ReferenceFrame> referenceFrames
) {

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
                continuityAnchors, audioAmbience, dialogueBeats, null);
    }
}
