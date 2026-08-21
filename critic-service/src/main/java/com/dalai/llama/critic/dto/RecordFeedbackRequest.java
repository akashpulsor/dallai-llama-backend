package com.dalai.llama.critic.dto;

public record RecordFeedbackRequest(
        boolean approved,
        String editLocations,
        String reason
) {
}
