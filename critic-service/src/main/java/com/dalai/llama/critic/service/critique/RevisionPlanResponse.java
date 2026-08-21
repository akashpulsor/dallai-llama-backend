package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.dto.shotcontext.ShotContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The revision planner's real output contract: always a valid single-shot revision (so there's
 * always something dispatchable), plus an optional decomposition recommendation the planner
 * raises when the shot is fundamentally too complex for one generation. Decomposition is
 * surfaced, never auto-executed -- creating the split shots is a human decision made through the
 * UI, not this pipeline (see the design note on {@code CritiqueResult}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RevisionPlanResponse(
        ShotContext revisedPlan,
        boolean decompositionRecommended,
        Integer suggestedShotCount,
        String decompositionReason
) {
}
