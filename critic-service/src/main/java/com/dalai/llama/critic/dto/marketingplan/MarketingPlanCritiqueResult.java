package com.dalai.llama.critic.dto.marketingplan;

import com.dalai.llama.critic.domain.CritiqueVerdict;

import java.util.List;
import java.util.UUID;

/** {@code revisedContent} is non-null only when the harness produced one -- the caller
 * (creative-planning-service) applies it if present, otherwise keeps the original, and only when
 * {@code verdict == PASS}. On {@code NEEDS_HUMAN_REVIEW} the caller must not finalize the plan. */
public record MarketingPlanCritiqueResult(
        UUID sessionId,
        CritiqueVerdict verdict,
        List<MarketingPlanCritiqueFindingView> findings,
        MarketingPlanContent revisedContent
) {
}
