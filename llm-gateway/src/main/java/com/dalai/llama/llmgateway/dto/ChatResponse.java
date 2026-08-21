package com.dalai.llama.llmgateway.dto;

import java.util.List;
import java.util.UUID;

public record ChatResponse(
        UUID jobId,
        String modelId,
        String response,
        UsageDto usage,
        long latencyMs,
        /** Set when the model chose to call one or more tools instead of (or alongside)
         * returning text -- the caller executes these and replies with role="tool" messages. */
        List<ToolCall> toolCalls
) {
    public ChatResponse(UUID jobId, String modelId, String response, UsageDto usage, long latencyMs) {
        this(jobId, modelId, response, usage, latencyMs, List.of());
    }
}
