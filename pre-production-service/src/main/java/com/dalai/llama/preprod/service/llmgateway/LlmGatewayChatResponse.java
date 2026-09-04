package com.dalai.llama.preprod.service.llmgateway;

import java.math.BigDecimal;
import java.util.UUID;

public record LlmGatewayChatResponse(
        UUID jobId,
        String modelId,
        String response,
        Usage usage,
        long latencyMs,
        /** e.g. Gemini's "SAFETY"/"PROHIBITED_CONTENT" -- null on an idempotent replay, never null
         * on a fresh dispatch. See llm-gateway's own ChatResponse for why this exists: an empty or
         * unexpected {@code response} used to give no way to tell "the model refused" from
         * "something else broke." */
        String finishReason
) {
    public record Usage(int inputTokens, int outputTokens, BigDecimal cost) {
    }
}
