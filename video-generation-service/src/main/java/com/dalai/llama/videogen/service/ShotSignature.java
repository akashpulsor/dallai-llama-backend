package com.dalai.llama.videogen.service;

/** Doc §21.2, trimmed to what's derivable from this v1's ShotContext. */
public record ShotSignature(
        boolean hasFace,
        boolean isMotionOnly,
        boolean requiresLipSync,
        /** Shot carries one or more dialogue-beat timestamps -- the router should prefer a model
         * that actually supports turning off native audio generation for these (see doc's
         * dialogue-beats design; {@code MODEL_RECOMMENDATION}'s prompt is the other half of this). */
        boolean hasDialogueBeats,
        String durationBucket,
        String qualityTier
) {
}
