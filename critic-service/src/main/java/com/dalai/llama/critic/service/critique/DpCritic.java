package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.CriticRole;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cinematographic consistency + physical plausibility: camera/lens/movement/focus/lighting
 * contradictions, scale, perspective, motion. */
@Component
class DpCritic extends AbstractLlmShotCritic {

    DpCritic(LlmGatewayClient llmGatewayClient, ObjectMapper objectMapper,
             @Value("${critic.llm-gateway.default-text-model}") String defaultModel) {
        super(llmGatewayClient, objectMapper, defaultModel);
    }

    @Override
    public CriticRole role() {
        return CriticRole.DP;
    }

    @Override
    protected String taskKey() {
        return "CRITIC_DP_REVIEW";
    }
}
