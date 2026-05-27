package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CreatorHistoryItemResponse(
        UUID id,
        String type,
        String topic,
        String title,
        String sourceLabel,
        UUID projectId,
        UUID lockedIdeaId,
        UUID storyIdeaId,
        UUID scriptId,
        UUID storyboardId,
        Map<String, Object> selectedIdea,
        String status,
        Integer durationSeconds,
        Integer imageCount,
        String preview,
        Map<String, Object> payload,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
