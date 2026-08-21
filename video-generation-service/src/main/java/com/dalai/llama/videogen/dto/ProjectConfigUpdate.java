package com.dalai.llama.videogen.dto;

public record ProjectConfigUpdate(
        FeatureFlags defaultFlags,
        boolean autoApprove
) {
}
