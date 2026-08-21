package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.CriticRole;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Creative-quality review: emotion, story, brand, visual hierarchy, premium feel. */
@Component
class DirectorCritic extends AbstractLlmShotCritic {

    DirectorCritic(LlmGatewayClient llmGatewayClient, ObjectMapper objectMapper,
                    @Value("${critic.llm-gateway.default-text-model}") String defaultModel) {
        super(llmGatewayClient, objectMapper, defaultModel);
    }

    @Override
    public CriticRole role() {
        return CriticRole.DIRECTOR;
    }

    @Override
    protected String taskKey() {
        return "CRITIC_DIRECTOR_REVIEW";
    }
}
