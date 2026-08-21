package com.dalai.llama.critic.dto;

import java.time.OffsetDateTime;

public record CritiqueThoughtView(
        String step,
        String message,
        OffsetDateTime createdAt
) {
}
