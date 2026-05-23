package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CreatorProjectResponse(
        UUID id,
        String projectId,
        String title,
        String status,
        String selectedPlatformCode,
        String selectedCategoryCode,
        String timeframe,
        String countryCode,
        Integer durationSeconds,
        UUID selectedTrendId,
        UUID selectedAudienceId,
        UUID selectedProfileId,
        UUID selectedIdeaId,
        UUID selectedStoryboardId,
        Map<String, Object> preferences,
        Map<String, Object> memorySnapshot,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
