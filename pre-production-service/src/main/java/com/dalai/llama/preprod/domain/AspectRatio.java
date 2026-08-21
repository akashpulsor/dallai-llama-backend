package com.dalai.llama.preprod.domain;

/** Wire-contract copy of video-generation-service's {@code AspectRatio} enum names -- Jackson
 * serializes by name (RATIO_16_9, ...), not by {@code wireValue()}, so the names must match
 * exactly for {@code ShotContextAssemblyService}'s outbound call to deserialize correctly. */
public enum AspectRatio {
    RATIO_16_9,
    RATIO_9_16,
    RATIO_1_1,
    RATIO_4_5,
    RATIO_21_9
}
