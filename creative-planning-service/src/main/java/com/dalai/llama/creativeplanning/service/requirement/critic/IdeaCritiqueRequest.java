package com.dalai.llama.creativeplanning.service.requirement.critic;

import java.util.List;

/** Wire-contract mirror of critic-service's own {@code IdeaCritiqueRequest}. */
public record IdeaCritiqueRequest(
        String briefText,
        String targetAudience,
        String campaignDirection,
        String referenceImageAnalysis,
        List<IdeaCandidateItem> candidates
) {
}
