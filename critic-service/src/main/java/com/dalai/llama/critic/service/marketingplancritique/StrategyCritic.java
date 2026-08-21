package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.domain.MarketingPlanCriticRole;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Internal coherence: does positioning follow from the market analysis, do objectives follow
 * from the strategy, and does referencedCaseStudyPatterns actually name specific real
 * companies/campaigns rather than going vague on generic framework names. */
@Component
class StrategyCritic extends AbstractLlmMarketingPlanCritic {

    StrategyCritic(LlmGatewayClient llmGatewayClient, ObjectMapper objectMapper,
                    @Value("${critic.llm-gateway.default-text-model}") String defaultModel) {
        super(llmGatewayClient, objectMapper, defaultModel);
    }

    @Override
    public MarketingPlanCriticRole role() {
        return MarketingPlanCriticRole.STRATEGY;
    }

    @Override
    protected String taskKey() {
        return "MARKETING_PLAN_CRITIC_STRATEGY";
    }
}
