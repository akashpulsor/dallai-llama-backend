package com.dalai.llama.creativeplanning.service.requirement.critic;

/** Wire-contract mirror of critic-service's own {@code IdeaCandidateItem}. */
public record IdeaCandidateItem(
        String title,
        String concept,
        String targetAudience,
        String campaignAngle,
        String keyMessage,
        String tone
) {
}
