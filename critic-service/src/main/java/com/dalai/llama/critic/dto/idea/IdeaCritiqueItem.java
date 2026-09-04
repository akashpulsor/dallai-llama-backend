package com.dalai.llama.critic.dto.idea;

import java.util.List;

/** {@code title} matches this critique back to its {@link IdeaCandidateItem} -- ideas don't have
 * an id yet at critique time (they're critiqued as a batch before any of them is persisted).
 * Scores are 0-100. {@code strengths}/{@code concerns} are the actual "why" behind the score --
 * shown directly on the idea card so a creator picking between options sees the reasoning, not
 * just a number. */
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
