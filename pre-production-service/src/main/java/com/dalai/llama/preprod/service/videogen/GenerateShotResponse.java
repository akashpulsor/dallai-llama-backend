package com.dalai.llama.preprod.service.videogen;

import java.math.BigDecimal;
import java.util.UUID;

public record GenerateShotResponse(
        UUID jobId,
        UUID promptId,
        String status,
        FeatureFlags effectiveFlags,
        BigDecimal estimatedCost,
        String recommendedModel,
        String recommendationReasoning
) {
}
