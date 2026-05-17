package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TrendPredictionRequest(
        @NotBlank String category,
        String platform,
        String country,
        Integer horizonHours,
        String predictionMode,
        List<UUID> sourceTrendIds,
        List<String> userSignals,
        String userContext,
        Map<String, Object> parameters
) {
}
