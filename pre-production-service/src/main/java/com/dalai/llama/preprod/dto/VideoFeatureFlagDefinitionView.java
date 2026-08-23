package com.dalai.llama.preprod.dto;

public record VideoFeatureFlagDefinitionView(
        String flagKey,
        String label,
        String description,
        Boolean defaultEnabled
) {
}
