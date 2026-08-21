package com.dalai.llama.videogen.service;

/** Doc §21.2, trimmed to what's derivable from this v1's ShotContext. */
public record ShotSignature(
        boolean hasFace,
        boolean isMotionOnly,
        boolean requiresLipSync,
        String durationBucket,
        String qualityTier
) {
}
