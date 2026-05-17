package com.dalai.llama.creator.dto.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TrendPredictionResponse(
        UUID promptRunId,
        String templateKey,
        Integer templateVersion,
        String provider,
        String model,
        String category,
        String platform,
        String country,
        Integer horizonHours,
        String predictionMode,
        Integer evidenceSignalCount,
        List<TrendPredictionItemResponse> predictions,
        Map<String, Object> aiOutput
) {
}
