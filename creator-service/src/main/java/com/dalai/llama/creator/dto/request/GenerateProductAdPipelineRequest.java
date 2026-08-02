package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GenerateProductAdPipelineRequest(
        String productUrl,
        String productName,
        List<String> productImageUrls,
        String campaignObjective,
        String targetAudience,
        @Size(max = 150) String ingredientDetails,
        String tone,
        String categoryCode,
        String platformCode,
        Integer durationSeconds,
        Integer conceptCount,
        Integer imageCount,
        String screenType,
        String pacingStyle,
        String imageProvider,
        String imageModel,
        Boolean noHumans,
        Boolean autoPlanShotTypes,
        Boolean generateImages,
        Boolean useWebSearch,
        Boolean autoPrepareVideoRun,
        UUID projectId,
        UUID lockedIdeaId,
        UUID storyIdeaId,
        UUID scriptId,
        Map<String, Object> existingBrief,
        Map<String, Object> brandContext,
        String idempotencyKey
) {
}
