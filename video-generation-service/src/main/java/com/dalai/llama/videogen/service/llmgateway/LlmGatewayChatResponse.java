package com.dalai.llama.videogen.service.llmgateway;

import java.math.BigDecimal;
import java.util.UUID;

public record LlmGatewayChatResponse(
        UUID jobId,
        String modelId,
        String response,
        LlmGatewayUsage usage,
        long latencyMs
) {
    public record LlmGatewayUsage(int inputTokens, int outputTokens, BigDecimal cost) {
    }
}
