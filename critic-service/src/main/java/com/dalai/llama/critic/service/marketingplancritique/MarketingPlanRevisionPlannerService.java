package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.dto.marketingplan.MarketingPlanContent;
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
 * Runs exactly once per marketing-plan critique session, only when a P1 finding exists -- takes
 * the original plan plus every role's findings and produces one revised {@code
 * MarketingPlanContent}. Marketing-plan sibling of {@code RevisionPlannerService}: same
 * bounded-single-pass discipline, never a retry loop.
 */
@Service
public class MarketingPlanRevisionPlannerService {

    private static final String TASK_KEY = "MARKETING_PLAN_REVISION";

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public MarketingPlanRevisionPlannerService(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${critic.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    public MarketingPlanRevisionResponse revise(UUID tenantId, MarketingPlanContent originalPlan,
                                                 List<MarketingPlanFindingForRevision> findings, String brandAndAudienceContext) {
        String planJson = writeJson(originalPlan, "marketing plan");
        String findingsJson = writeJson(findings, "findings");

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                TASK_KEY + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of("marketingPlanJson", planJson, "findingsJson", findingsJson,
                                "brandAndAudienceContext", brandAndAudienceContext)));

        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CriticException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        MarketingPlanRevisionResponse parsed;
        try {
            parsed = objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), MarketingPlanRevisionResponse.class);
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
