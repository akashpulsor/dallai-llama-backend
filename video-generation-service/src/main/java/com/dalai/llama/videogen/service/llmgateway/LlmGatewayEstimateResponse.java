package com.dalai.llama.videogen.service.llmgateway;

import java.math.BigDecimal;

public record LlmGatewayEstimateResponse(
        String modelId,
        int estimatedInputTokens,
        BigDecimal estimatedCost,
        Long rateCardVersion
) {
}
