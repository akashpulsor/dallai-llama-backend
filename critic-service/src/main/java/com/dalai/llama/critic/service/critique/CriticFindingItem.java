package com.dalai.llama.critic.service.critique;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** OBSERVATION -> RISK -> CAUSE -> CORRECTION, exactly as returned by a critic's LLM call --
 * {@code severity} is parsed tolerantly onto {@link com.dalai.llama.critic.domain.CritiqueSeverity}
 * by the orchestrator, not here. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CriticFindingItem(
        String observation,
        String risk,
        String cause,
        String correction,
        String severity
) {
}
