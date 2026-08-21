package com.dalai.llama.creativeplanning.dto;

import java.util.List;
import java.util.UUID;

/** What {@code POST /v1/marketing-plans} (and {@code .../revise}) returns: the plan as generated
 * (possibly after the one bounded revision pass), the harness's findings so the caller can see
 * why, not just what, and {@code critiqueSessionId} so the caller can pull the harness's complete
 * thought log via {@code GET /v1/critiques/{sessionId}/thoughts} on critic-service -- same
 * transparency contract as {@code ShotDispatchResponse.critiqueSessionId} for shot generation. */
public record MarketingPlanGenerationResultView(
        MarketingPlanView plan,
        UUID critiqueSessionId,
        List<MarketingPlanCritiqueFindingView> findings
) {
}
