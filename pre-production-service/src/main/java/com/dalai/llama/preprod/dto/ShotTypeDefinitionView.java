package com.dalai.llama.preprod.dto;

public record ShotTypeDefinitionView(
        String code,
        String label,
        String description,
        Boolean requiresVideoGeneration,
        Boolean requiresMotionGraphics
) {
}
