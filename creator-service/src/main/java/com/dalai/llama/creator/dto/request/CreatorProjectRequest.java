package com.dalai.llama.creator.dto.request;

import java.util.Map;
import java.util.UUID;

public record CreatorProjectRequest(
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
        Map<String, Object> memorySnapshot
) {
}
