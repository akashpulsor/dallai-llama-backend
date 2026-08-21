package com.dalai.llama.creativeplanning.service.marketingplan.critic;

import com.dalai.llama.creativeplanning.service.marketingplan.MarketingPlanContent;

import java.util.UUID;

/** Wire-contract mirror of critic-service's own {@code MarketingPlanCritiqueRequest} -- same
 * convention as {@code ShotContext} being mirrored between pre-production-service and
 * critic-service: each side owns its copy of the contract it depends on. */
public record MarketingPlanCritiqueApiRequest(
        UUID planId,
        MarketingPlanContent content,
        String brandAndAudienceContext
) {
}
