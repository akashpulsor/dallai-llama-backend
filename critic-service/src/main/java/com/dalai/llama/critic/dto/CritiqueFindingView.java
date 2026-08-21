package com.dalai.llama.critic.dto;

import com.dalai.llama.critic.domain.CriticRole;
import com.dalai.llama.critic.domain.CritiqueSeverity;

public record CritiqueFindingView(
        CriticRole role,
        String observation,
        String risk,
        String cause,
        String correction,
        CritiqueSeverity severity
) {
}
