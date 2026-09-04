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
        List<ToolCall> toolCalls,
        /** The provider's own reason the response looks the way it does (e.g. Gemini's "STOP",
         * "SAFETY", "PROHIBITED_CONTENT") -- was parsed internally already but discarded before
         * reaching callers, so an empty/unexpected {@code response} (e.g. an image call that came
         * back with no image) had no way to tell "the model refused" from "something else broke."
         * Null on an idempotent replay (a completed job's original finishReason isn't persisted),
         * never null on a fresh dispatch. */
        String finishReason
) {
    public ChatResponse(UUID jobId, String modelId, String response, UsageDto usage, long latencyMs) {
        this(jobId, modelId, response, usage, latencyMs, List.of(), null);
    }
}
