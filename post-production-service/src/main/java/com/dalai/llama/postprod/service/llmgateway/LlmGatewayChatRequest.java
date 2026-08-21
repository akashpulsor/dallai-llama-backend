package com.dalai.llama.postprod.service.llmgateway;

import java.util.List;
import java.util.Map;

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
