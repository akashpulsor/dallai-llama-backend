package com.dalai.llama.preprod.service.critic;

import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;

import java.util.List;
import java.util.UUID;

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
