package com.dalai.llama.preprod.domain;

/** Wire-contract copy of video-generation-service's {@code EmotionalArcPosition} enum names.
 * Computed at assembly time from shot ordering (see ShotContextAssemblyService) -- the full
 * EMOTIONAL_ARC_BEAT entity/editor is deferred past this v1 slice. */
public enum EmotionalArcPosition {
    SETUP,
    RISING,
    CLIMAX,
    RESOLUTION
}
