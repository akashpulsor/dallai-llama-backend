package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CampaignAngleSuggestionRequest(
        UUID projectId,
        @Size(max = 4000) String ideaText,
        @Size(max = 80) String topicType,
        @Size(max = 80) String adFormat,
        @Size(max = 4000) String campaignObjective,
        @Size(max = 240) String targetAudience,
        @Size(max = 80) String productionStyle,
        @Size(max = 80) String dialogueLanguage,
        @Size(max = 80) String screenType,
        @Size(max = 80) String storytellingType,
        @Size(max = 80) String hookLens,
        @Min(3) @Max(600) Integer durationSeconds,
        @Size(max = 8) List<@Size(max = 80) String> selectedShotTypes,
        Map<String, Object> brandContext,
        Map<String, Object> productIntelligenceBrief
) {
}
