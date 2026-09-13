package com.dalai.llama.critic.service.llmgateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LlmGatewayChatRequest(
        String modelId,
        List<LlmGatewayMessage> messages,
        Map<String, Object> params,
        String taskKey,
        Map<String, String> templateVariables,
        /** Project attribution for per-project cost rollup -- llm-gateway persists it on the job
         * row. Null for calls with no project context. */
        UUID projectId
) {
    /** Back-compat: existing 5-arg call sites keep working with no project attribution (null). */
    public LlmGatewayChatRequest(String modelId, List<LlmGatewayMessage> messages, Map<String, Object> params,
                                  String taskKey, Map<String, String> templateVariables) {
        this(modelId, messages, params, taskKey, templateVariables, null);
    }

    public record LlmGatewayMessage(String role, String content) {
    }
}
