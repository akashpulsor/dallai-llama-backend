package com.dalai.llama.videogen.service.llmgateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mirrors llm-gateway's own {@code ChatRequest} DTO -- video-generation-service is a caller,
 * not the owner, of this shape. */
public record LlmGatewayChatRequest(
        String modelId,
        List<LlmGatewayMessage> messages,
        Map<String, Object> params,
        String taskKey,
        Map<String, String> templateVariables,
        /** Project attribution for per-project cost rollup. Null for calls with no project context. */
        UUID projectId,
        /** Platform language selection -- never a provider code or parameter name. */
        LlmGatewayLanguageSelection language
) {
    /** Back-wards: existing 5-arg call sites keep working with no project or language. */
    public LlmGatewayChatRequest(String modelId, List<LlmGatewayMessage> messages, Map<String, Object> params,
                                  String taskKey, Map<String, String> templateVariables) {
        this(modelId, messages, params, taskKey, templateVariables, null, null);
    }

    /** Back-wards: existing 6-arg call sites keep working with no language. */
    public LlmGatewayChatRequest(String modelId, List<LlmGatewayMessage> messages, Map<String, Object> params,
                                    String taskKey, Map<String, String> templateVariables, UUID projectId) {
        this(modelId, messages, params, taskKey, templateVariables, projectId, null);
    }

    public LlmGatewayChatRequest withProjectId(UUID projectId) {
        return new LlmGatewayChatRequest(modelId(), messages(), params(), taskKey(), templateVariables(), projectId, language());
    }

    public record LlmGatewayMessage(String role, String content) {
    }
}
