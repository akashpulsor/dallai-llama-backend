package com.dalai.llama.preprod.dto;

/** One foley cue on a shot -- the millisecond it lands, what kind of sound it is, and what it is.
 * Mirrors video-generation-service's FoleyCueView so the prepare bundle deserializes straight
 * into it. */
public record ShotFoleyCueView(
        Integer timestampMs,
        String cueType,
        String description
) {
}
