package com.dalai.llama.critic.dto;

import com.dalai.llama.critic.domain.CritiqueVerdict;
import com.dalai.llama.critic.dto.shotcontext.ShotContext;

import java.util.List;
import java.util.UUID;

/** {@code revisedPlan} is non-null only when the harness produced one -- the caller (pre-production-
 * service) dispatches {@code revisedPlan} if present, otherwise the original plan, and only when
 * {@code verdict == PASS}. On {@code NEEDS_HUMAN_REVIEW} the caller must not dispatch.
 * <p>
 * {@code decompositionRecommended} is a signal, never an instruction: the revision planner
 * decided a single-shot revision cannot really fix the plan (it's fundamentally too complex for
 * one generation) and suggests splitting it into {@code suggestedShotCount} shots. This pipeline
 * never creates those shots itself -- {@code revisedPlan} is still returned as the best
 * single-shot fallback, and a human decides whether to act on the recommendation via the UI. */
public record CritiqueResult(
        UUID sessionId,
        CritiqueVerdict verdict,
        List<CritiqueFindingView> findings,
        ShotContext revisedPlan,
        boolean decompositionRecommended,
        Integer suggestedShotCount,
        String decompositionReason
) {
}
