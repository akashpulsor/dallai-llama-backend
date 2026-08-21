package com.dalai.llama.videogen.service;

import java.math.BigDecimal;
import java.util.UUID;

public record DispatchResult(
        UUID llmGatewayJobId,
        String outputUri,
        BigDecimal actualCost,
        String currency
) {
}
