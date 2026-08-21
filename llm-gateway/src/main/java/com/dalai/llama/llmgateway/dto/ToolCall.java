package com.dalai.llama.llmgateway.dto;

import java.util.Map;

/**
 * What the model wants executed. The gateway never runs this -- the caller executes it against
 * their own tool implementation and sends the result back as a follow-up {@link ChatMessage}
 * with {@code role="tool"} and {@code toolCallId} set to this call's {@code id}.
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {
}
