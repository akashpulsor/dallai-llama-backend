package com.dalai.llama.postprod.service;

import java.math.BigDecimal;
import java.util.UUID;

public record VoiceSynthesisResult(
        UUID llmGatewayJobId,
        String audioUrl,
        BigDecimal actualCost
) {
}
