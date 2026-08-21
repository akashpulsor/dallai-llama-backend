package com.dalai.llama.critic.service.marketingplancritique;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** OBSERVATION -> RISK -> CAUSE -> CORRECTION, exactly as returned by a marketing-plan critic's
 * LLM call -- marketing-plan sibling of {@code CriticFindingItem}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketingPlanCriticFindingItem(
        String observation,
        String risk,
        String cause,
        String correction,
        String severity
) {
}
