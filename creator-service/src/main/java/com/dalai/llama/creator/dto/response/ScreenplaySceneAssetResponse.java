package com.dalai.llama.creator.dto.response;

import com.dalai.llama.creator.domain.ScreenplaySceneAssetType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ScreenplaySceneAssetResponse(
        UUID id,
        UUID scriptId,
        UUID runId,
        Integer shotNumber,
        ScreenplaySceneAssetType assetType,
        String status,
        String videoUrl,
        String contentType,
        Long sizeBytes,
        Integer durationSeconds,
        String provider,
        boolean accepted,
        OffsetDateTime acceptedAt,
        String acceptedBy,
        boolean combined,
        OffsetDateTime createdAt
) {
}
