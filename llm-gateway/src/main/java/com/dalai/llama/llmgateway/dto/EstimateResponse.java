package com.dalai.llama.llmgateway.dto;

import java.math.BigDecimal;

public record EstimateResponse(
        String modelId,
        int estimatedInputTokens,
        BigDecimal estimatedCost,
        Long rateCardVersion
) {
}
