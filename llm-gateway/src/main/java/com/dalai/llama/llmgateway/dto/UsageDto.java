package com.dalai.llama.llmgateway.dto;

import java.math.BigDecimal;

public record UsageDto(
        int inputTokens,
        int outputTokens,
        BigDecimal cost
) {
}
