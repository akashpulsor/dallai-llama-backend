package com.dalai.llama.preprod.dto;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.preprod.service.critic.CritiqueFindingView;
import com.dalai.llama.preprod.service.critic.CritiqueVerdict;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code generationJobId}/{@code status}/{@code externalJobId}/{@code externalPromptId} are null
 * when {@code critiqueVerdict == NEEDS_HUMAN_REVIEW} -- the pre-flight harness blocked dispatch,
 * see {@code findings} for why. {@code decompositionRecommended} is a signal only -- this service
 * still dispatched (or blocked) using the single-shot plan; splitting into multiple shots is a
 * human decision made through the UI, not something this pipeline does automatically.
 * {@code recommendedModel}/{@code recommendationReasoning}/{@code estimatedCost} come straight
 * from video-generation-service's own response -- this service makes no model choice itself.
 * With {@code autoApprove=false} the caller gets these plus {@code externalPromptId} back without
 * anything actually generating yet; fetching/approving/rejecting that prompt from here on is
 * video-generation-service's own JWT-authenticated {@code /v1/prompts}/{@code /v1/jobs} surface,
 * called directly by the frontend, not proxied through this service. */
public record ShotDispatchResponse(
        UUID generationJobId,
        JobLifecycleStatus status,
        UUID externalJobId,
        UUID externalPromptId,
        String recommendedModel,
        String recommendationReasoning,
        BigDecimal estimatedCost,
        UUID critiqueSessionId,
        CritiqueVerdict critiqueVerdict,
        List<CritiqueFindingView> findings,
        boolean decompositionRecommended,
        Integer suggestedShotCount,
        String decompositionReason
) {
}
