package com.dalai.llama.videogen.service.llmgateway;

import java.util.List;
import java.util.Map;

/** Mirrors llm-gateway's own {@code ChatRequest} DTO -- video-generation-service is a caller,
 * not the owner, of this shape. */
public record LlmGatewayChatRequest(
        String modelId,
        List<LlmGatewayMessage> messages,
        Map<String, Object> params,
        String taskKey,
        Map<String, String> templateVariables
) {
    public record LlmGatewayMessage(String role, String content) {
    }
}
