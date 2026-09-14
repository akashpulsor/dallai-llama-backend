package com.dalai.llama.chat.service.llmgateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A call to llm-gateway.
 *
 * <p>{@code projectId} is what makes the call traceable afterwards. llm-gateway writes one
 * {@code llm_job} row per call carrying its cost, and a row with no project cannot be attributed
 * to anything -- which is what had been happening to every embedding this service made: real spend,
 * logged, but belonging to no project. The field was simply absent from this record, so there was
 * no way to send one.
 *
 * <p>Nullable on purpose. Not every call has a project scope, and an unscoped call is better logged
 * unattributed than not logged at all.
 */
public record LlmGatewayChatRequest(
        String modelId,
        List<LlmGatewayMessage> messages,
        Map<String, Object> params,
        String taskKey,
        Map<String, String> templateVariables,
        UUID projectId
) {
    /** Keeps the call sites that have no project to give from having to say so. */
    public LlmGatewayChatRequest(
            String modelId,
            List<LlmGatewayMessage> messages,
            Map<String, Object> params,
            String taskKey,
            Map<String, String> templateVariables
    ) {
        this(modelId, messages, params, taskKey, templateVariables, null);
    }

    public LlmGatewayChatRequest withProjectId(UUID projectId) {
        return new LlmGatewayChatRequest(modelId, messages, params, taskKey, templateVariables, projectId);
    }

    public record LlmGatewayMessage(String role, String content) {
    }
}
