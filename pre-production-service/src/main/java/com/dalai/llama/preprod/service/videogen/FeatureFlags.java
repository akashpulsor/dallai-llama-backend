package com.dalai.llama.preprod.service.videogen;

public record FeatureFlags(
        FlagState dialogue,
        FlagState captions
) {
}
