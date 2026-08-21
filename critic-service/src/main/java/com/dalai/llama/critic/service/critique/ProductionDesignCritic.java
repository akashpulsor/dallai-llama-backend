package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.CriticRole;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Environment/product/continuity review: does the environment, product placement, and continuity
 * anchors hold together across this shot. */
@Component
class ProductionDesignCritic extends AbstractLlmShotCritic {

    ProductionDesignCritic(LlmGatewayClient llmGatewayClient, ObjectMapper objectMapper,
                            @Value("${critic.llm-gateway.default-text-model}") String defaultModel) {
        super(llmGatewayClient, objectMapper, defaultModel);
    }

    @Override
    public CriticRole role() {
        return CriticRole.PRODUCTION_DESIGN;
    }

    @Override
    protected String taskKey() {
        return "CRITIC_PRODUCTION_DESIGN_REVIEW";
    }
}
