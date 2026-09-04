package com.dalai.llama.creativeplanning.service.requirement.critic;

import java.util.List;

/** Wire-contract mirror of critic-service's own {@code IdeaCritiqueItem}. */
public record IdeaCritiqueItem(
        String title,
        IdeaCritiqueVerdict verdict,
        int completenessScore,
        int storyScore,
        int distinctivenessScore,
        List<String> strengths,
        List<String> concerns
) {
}
