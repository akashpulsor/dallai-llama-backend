package com.dalai.llama.critic.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SimilarFeedbackView(
        UUID sessionId,
        boolean approved,
        String editLocations,
        String reason,
        OffsetDateTime createdAt
) {
}
