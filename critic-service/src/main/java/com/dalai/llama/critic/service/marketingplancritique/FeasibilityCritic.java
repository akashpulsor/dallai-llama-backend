package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.domain.MarketingPlanCriticRole;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Is the plan realistic for the stated budget tier -- channel mix, content cadence, and
 * timelines that no team could execute on the stated budget get flagged here. */
@Component
class FeasibilityCritic extends AbstractLlmMarketingPlanCritic {

    FeasibilityCritic(LlmGatewayClient llmGatewayClient, ObjectMapper objectMapper,
                       @Value("${critic.llm-gateway.default-text-model}") String defaultModel) {
        super(llmGatewayClient, objectMapper, defaultModel);
    }

    @Override
    public MarketingPlanCriticRole role() {
        return MarketingPlanCriticRole.FEASIBILITY;
    }

    @Override
    protected String taskKey() {
        return "MARKETING_PLAN_CRITIC_FEASIBILITY";
    }
}
