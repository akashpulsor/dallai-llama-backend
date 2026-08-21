package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.dto.shotcontext.ShotContext;
import com.dalai.llama.critic.service.CriticException;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared serialize-call-parse mechanics for every role critic -- each subclass supplies only its
 * own {@code taskKey()} (the llm-gateway prompt_template task key for that role's review). */
abstract class AbstractLlmShotCritic implements ShotCritic {

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    protected AbstractLlmShotCritic(LlmGatewayClient llmGatewayClient, ObjectMapper objectMapper, String defaultModel) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    protected abstract String taskKey();

    @Override
    public List<CriticFindingItem> critique(UUID tenantId, ShotContext plan) {
        String planJson = writePlanJson(plan);
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                taskKey() + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, taskKey(), Map.of("shotPlanJson", planJson)));
        CriticResponse parsed = parseResponse(response);
        return parsed.findings() == null ? List.of() : parsed.findings();
    }

    private String writePlanJson(ShotContext plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception ex) {
            throw CriticException.badRequest("Could not serialize shot plan for critique: " + ex.getMessage());
        }
    }

    private CriticResponse parseResponse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CriticException.upstream("llm-gateway returned no content for " + taskKey());
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), CriticResponse.class);
        } catch (Exception ex) {
            throw CriticException.upstream("Could not parse " + taskKey() + " response as JSON: " + ex.getMessage());
        }
    }
}
