package com.dalai.llama.postprod.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Shared result shape for foley and music generation -- both are "a prompt in, an audio URL
 * out" call against llm-gateway. */
public record AudioGenerationResult(
        UUID llmGatewayJobId,
        String audioUrl,
        BigDecimal actualCost
) {
}
