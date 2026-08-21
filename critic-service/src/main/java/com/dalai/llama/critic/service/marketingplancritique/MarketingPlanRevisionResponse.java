package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.dto.marketingplan.MarketingPlanContent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The revision planner's output contract for a marketing plan: always a valid, complete revised
 * plan (so there's always something the caller can finalize). No decomposition concept here --
 * unlike a shot, a marketing plan is never split into multiple generation units, so this is
 * simpler than {@code RevisionPlanResponse}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketingPlanRevisionResponse(
        MarketingPlanContent revisedPlan
) {
}
