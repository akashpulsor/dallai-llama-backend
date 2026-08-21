package com.dalai.llama.critic.dto.marketingplan;

import com.dalai.llama.critic.domain.CritiqueSeverity;
import com.dalai.llama.critic.domain.MarketingPlanCriticRole;

public record MarketingPlanCritiqueFindingView(
        MarketingPlanCriticRole role,
        String observation,
        String risk,
        String cause,
        String correction,
        CritiqueSeverity severity
) {
}
