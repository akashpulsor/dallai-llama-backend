package com.dalai.llama.creator.dto.request;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ShotTakeStudioPolishRequest(
        String editNote,
        String provider,
        String model,
        String providerMode,
        String preset,
        Boolean generatePlateFromReference,
        String plateMode,
        List<Double> candidateTimestampsSeconds,
        Long seed,
        UUID recipeSourceVariantId,
        Map<String, Object> transformationRecipe,
        Map<String, Object> studioPolishControls,
        Map<String, Object> overrides
) {
}
