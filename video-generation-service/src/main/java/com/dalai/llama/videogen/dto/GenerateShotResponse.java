package com.dalai.llama.videogen.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record GenerateShotResponse(
        UUID jobId,
        UUID promptId,
        String status,
        FeatureFlags effectiveFlags,
        BigDecimal estimatedCost,
        String recommendedModel,
        String recommendationReasoning,
        List<FoleyCueView> foleyCues
) {
}
