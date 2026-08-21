package com.dalai.llama.creativeplanning.service.marketingplan.critic;

import com.dalai.llama.creativeplanning.domain.MarketingPlanCritiqueVerdict;
import com.dalai.llama.creativeplanning.dto.MarketingPlanCritiqueFindingView;
import com.dalai.llama.creativeplanning.service.marketingplan.MarketingPlanContent;

import java.util.List;
import java.util.UUID;

/** Wire-contract mirror of critic-service's own {@code MarketingPlanCritiqueResult}.
 * {@code revisedContent} is non-null only when the harness produced one -- the caller applies it
 * if present and only when {@code verdict == PASS}; on {@code NEEDS_HUMAN_REVIEW} the caller must
 * not finalize the plan (see {@code MarketingPlanGenerationService}). */
public record MarketingPlanCritiqueApiResult(
        UUID sessionId,
        MarketingPlanCritiqueVerdict verdict,
        List<MarketingPlanCritiqueFindingView> findings,
        MarketingPlanContent revisedContent
) {
}
