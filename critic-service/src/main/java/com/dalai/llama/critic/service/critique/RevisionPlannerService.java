package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.dto.shotcontext.ShotContext;
import com.dalai.llama.critic.service.CriticException;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs exactly once per critique session, only when a P1 finding exists -- takes the original
 * plan plus every role's findings and produces one revised {@code ShotContext}. This is the
 * "not a critique report, a better plan" principle: the output IS the next thing dispatched, not
 * something a caller has to re-interpret.
 */
@Service
public class RevisionPlannerService {

    private static final String TASK_KEY = "CRITIC_REVISION_PLAN";

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public RevisionPlannerService(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${critic.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    public RevisionPlanResponse revise(UUID tenantId, ShotContext originalPlan, List<FindingForRevision> findings) {
        String planJson = writeJson(originalPlan, "shot plan");
        String findingsJson = writeJson(findings, "findings");

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                TASK_KEY + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of("shotPlanJson", planJson, "findingsJson", findingsJson)));

        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CriticException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        RevisionPlanResponse parsed;
        try {
            parsed = objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), RevisionPlanResponse.class);
        } catch (Exception ex) {
            throw CriticException.upstream("Could not parse " + TASK_KEY + " response: " + ex.getMessage());
        }
        if (parsed.revisedPlan() == null) {
            throw CriticException.upstream(TASK_KEY + " response had no revisedPlan");
        }
        return parsed;
    }

    private String writeJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw CriticException.badRequest("Could not serialize " + label + " for revision: " + ex.getMessage());
        }
    }
}
