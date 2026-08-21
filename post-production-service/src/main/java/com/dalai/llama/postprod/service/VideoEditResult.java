package com.dalai.llama.postprod.service;

import java.math.BigDecimal;
import java.util.UUID;

public record VideoEditResult(
        UUID llmGatewayJobId,
        String editedVideoUrl,
        BigDecimal actualCost
) {
}
