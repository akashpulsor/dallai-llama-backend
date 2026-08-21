package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.domain.MarketingPlanCriticRole;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Is this plan actually specific to the stated target audience, or generic boilerplate that
 * could apply to any brand in any category. */
@Component
class AudienceFitCritic extends AbstractLlmMarketingPlanCritic {

    AudienceFitCritic(LlmGatewayClient llmGatewayClient, ObjectMapper objectMapper,
                       @Value("${critic.llm-gateway.default-text-model}") String defaultModel) {
        super(llmGatewayClient, objectMapper, defaultModel);
    }

    @Override
    public MarketingPlanCriticRole role() {
        return MarketingPlanCriticRole.AUDIENCE_FIT;
    }

    @Override
    protected String taskKey() {
        return "MARKETING_PLAN_CRITIC_AUDIENCE_FIT";
    }
}
