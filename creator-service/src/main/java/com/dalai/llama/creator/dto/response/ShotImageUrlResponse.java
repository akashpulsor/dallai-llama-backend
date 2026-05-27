package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ShotImageUrlResponse(
        UUID scriptId,
        UUID storyboardId,
        UUID projectId,
        UUID storyIdeaId,
        Integer shotNumber,
        UUID storyboardImageAssetId,
        String storyboardObjectKey,
        String storyboardImageUrl,
        UUID lightingImageAssetId,
        String lightingObjectKey,
        String lightingImageUrl,
        UUID cameraPlanImageAssetId,
        String cameraPlanObjectKey,
        String cameraPlanImageUrl,
        String screenType,
        Integer renderWidth,
        Integer renderHeight,
        OffsetDateTime updatedAt
) {
}
