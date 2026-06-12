package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ShotTakeResponse(
        UUID takeId,
        UUID projectId,
        UUID scriptId,
        Integer shotNumber,
        UUID assetId,
        String assetUrl,
        String contentType,
        Long sizeBytes,
        UUID referenceFrameAssetId,
        String referenceFrameUrl,
        String referenceFrameContentType,
        String status,
        String reviewStatus,
        Boolean accepted,
        String userNotes,
        Map<String, Object> mediaAnalysis,
        Map<String, Object> validationSummary,
        List<ShotTakeReviewResponse> reviews,
        List<ShotTakeEnhancementVariantResponse> variants,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
