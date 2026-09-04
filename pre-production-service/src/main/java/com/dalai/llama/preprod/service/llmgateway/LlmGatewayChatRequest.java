package com.dalai.llama.preprod.service.llmgateway;

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

    /** Same 5-arg shape plus a project id -- for call sites that have a project in scope. */
    public LlmGatewayChatRequest withProjectId(UUID projectId) {
        return new LlmGatewayChatRequest(modelId(), messages(), params(), taskKey(), templateVariables(), projectId);
    }

    public record LlmGatewayMessage(String role, String content, List<String> imageDataUris) {
        public LlmGatewayMessage(String role, String content) {
            this(role, content, null);
        }
    }
}
