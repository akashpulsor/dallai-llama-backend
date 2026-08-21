package com.dalai.llama.videogen.service;

import java.math.BigDecimal;

public record CostEstimate(
        BigDecimal estimatedCost,
        String currency
) {
}
