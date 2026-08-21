package com.dalai.llama.llmgateway.service.provider;

import com.dalai.llama.llmgateway.dto.ToolCall;

import java.util.List;

public record LlmResponse(
        String content,
        int inputTokens,
        int outputTokens,
        String finishReason,
        List<ToolCall> toolCalls
) {
}
