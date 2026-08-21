package com.dalai.llama.trendintel.service.llmgateway;

import java.math.BigDecimal;
import java.util.UUID;

public record LlmGatewayChatResponse(
        UUID jobId,
        String modelId,
        String response,
        Usage usage,
        long latencyMs
) {
    public record Usage(int inputTokens, int outputTokens, BigDecimal cost) {
    }
}
