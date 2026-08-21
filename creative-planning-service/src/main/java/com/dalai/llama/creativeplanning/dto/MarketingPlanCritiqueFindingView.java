package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.MarketingPlanCriticRole;
import com.dalai.llama.creativeplanning.domain.MarketingPlanCritiqueSeverity;

public record MarketingPlanCritiqueFindingView(
        MarketingPlanCriticRole role,
        String observation,
        String risk,
        String cause,
        String correction,
        MarketingPlanCritiqueSeverity severity
) {
}
