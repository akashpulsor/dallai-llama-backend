package com.dalai.llama.critic.dto.idea;

import jakarta.validation.constraints.NotBlank;

/** Mirror of creative-planning-service's own generated-idea shape -- just the fields a critic
 * needs to judge, not the full persisted entity. */
public record IdeaCandidateItem(
        @NotBlank String title,
        String concept,
        String targetAudience,
        String campaignAngle,
        String keyMessage,
        String tone
) {
}
