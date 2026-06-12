package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ShotTakeReviewResponse(
        UUID reviewId,
        UUID takeId,
        UUID generationJobId,
        String status,
        Double score,
        Map<String, Object> checks,
        List<Map<String, Object>> soundTimeline,
        String message,
        OffsetDateTime createdAt
) {
}
