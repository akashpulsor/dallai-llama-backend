package com.dalai.llama.postprod.service;

import java.math.BigDecimal;
import java.util.UUID;

public record LipSyncResult(
        UUID llmGatewayJobId,
        String outputUri,
        BigDecimal actualCost
) {
}
