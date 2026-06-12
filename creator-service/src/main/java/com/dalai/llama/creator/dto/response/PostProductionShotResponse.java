package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record PostProductionShotResponse(
        UUID scriptId,
        UUID projectId,
        Integer shotNumber,
        String title,
        Double startTime,
        Double endTime,
        Double durationSeconds,
        String shotType,
        String cameraAngle,
        String cameraMovement,
        String lensSuggestion,
        String storyboardImageUrl,
        String lightingImageUrl,
        String cameraPlanImageUrl,
        UUID storyboardImageAssetId,
        UUID lightingImageAssetId,
        UUID cameraPlanImageAssetId,
        Map<String, Object> shotPayload,
        Map<String, Object> storyboardTag,
        Map<String, Object> lightingBuildSheetTag,
        Map<String, Object> cameraPlanSheetTag,
        OffsetDateTime updatedAt
) {
}
