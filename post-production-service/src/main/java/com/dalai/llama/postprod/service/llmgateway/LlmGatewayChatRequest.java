package com.dalai.llama.postprod.service.llmgateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A call to llm-gateway.
 *
 * <p>{@code projectId} is what makes the call traceable and groupable afterwards. llm-gateway
 * writes one {@code llm_job} row per call carrying its cost, and a row with no project cannot be
 * attributed to anything. The field was absent here, so every dub, lip-sync, voice clone and
 * synthesis this service ran -- the expensive calls -- was logged as spend belonging to no project,
 * even though the job that triggered it knew exactly which project it was for.
 *
 * <p>Nullable on purpose. The standalone endpoints (upscale from the AI editor, the foley and music
 * test routes) genuinely have no project: they act on an arbitrary hosted URL. Those stay
 * unattributed rather than being given an invented id.
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

    public record LlmGatewayMessage(String role, String content) {
    }
}
