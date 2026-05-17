package com.dalai.llama.creator.dto.response;

import java.util.UUID;

public record StoryboardSceneResponse(
        UUID sceneId,
        UUID imageAssetId,
        Integer shotNumber,
        String title,
        String startTime,
        String endTime,
        Integer durationSeconds,
        String shotType,
        String cameraAngle,
        String cameraMovement,
        String lensSuggestion,
        Integer fps,
        String objectKey,
        String signedUrl,
        String sketchPrompt
) {
}
