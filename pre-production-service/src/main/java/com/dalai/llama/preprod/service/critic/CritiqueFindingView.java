package com.dalai.llama.preprod.service.critic;

public record CritiqueFindingView(
        CriticRole role,
        String observation,
        String risk,
        String cause,
        String correction,
        CritiqueSeverity severity
) {
}
