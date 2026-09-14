package com.dalai.llama.creativeplanning.service.llmgateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A call to llm-gateway.
 *
 * <p>{@code projectId} is what makes the call traceable and groupable afterwards. llm-gateway
 * writes one {@code llm_job} row per call carrying its cost, and a row with no project cannot be
 * attributed to anything. The field was absent from this record, so this service had no way to
 * send one even where it plainly knew the project.
 *
 * <p>Nullable on purpose: several calls here are genuinely not project-scoped (campaign chat,
 * marketing-plan generation, standalone reference-image analysis). Those stay unattributed rather
 * than being given an invented id -- a log that looks complete but is wrong is worse than one with
 * a visible gap.
 */
public record LlmGatewayChatRequest(
        String modelId,
        List<LlmGatewayMessage> messages,
        Map<String, Object> params,
        String taskKey,
        Map<String, String> templateVariables,
        UUID projectId
) {
    /** Keeps the call sites with no project to give from having to say so. */
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

    public record LlmGatewayMessage(String role, String content, List<String> imageDataUris) {
        public LlmGatewayMessage(String role, String content) {
            this(role, content, null);
        }
    }
}
