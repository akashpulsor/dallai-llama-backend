package com.dalai.llama.critic.dto.idea;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** {@code referenceImageAnalysis} is the same text {@code
 * ReferenceMaterialAnalysisService#summarizeForRequirement} feeds idea generation itself --
 * scoring "completeness" against it too means an idea that ignores what the client's reference
 * images actually show gets marked down, not just one that ignores the brief text. */
public record IdeaCritiqueRequest(
        @NotBlank String briefText,
        String targetAudience,
        String campaignDirection,
        String referenceImageAnalysis,
        @NotEmpty @Valid List<IdeaCandidateItem> candidates
) {
}
