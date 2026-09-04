package com.dalai.llama.postprod.service;

import java.math.BigDecimal;
import java.util.UUID;

/** "A video URL in, a bigger/cleaner video URL out" call against llm-gateway -- same shape as
 * {@link AudioGenerationResult}, just video instead of audio. */
public record UpscaleGenerationResult(
        UUID llmGatewayJobId,
        String videoUrl,
        BigDecimal actualCost
) {
}
