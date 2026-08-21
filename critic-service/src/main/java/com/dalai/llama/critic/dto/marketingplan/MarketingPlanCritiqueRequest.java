package com.dalai.llama.critic.dto.marketingplan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MarketingPlanCritiqueRequest(
        @NotNull UUID planId,
        @NotNull MarketingPlanContent content,
        @NotBlank String brandAndAudienceContext
) {
}
