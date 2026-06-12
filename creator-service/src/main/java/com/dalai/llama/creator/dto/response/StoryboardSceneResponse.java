package com.dalai.llama.creator.dto.response;

import java.util.Map;
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
        String sketchPrompt,
        UUID lightingImageAssetId,
        String lightingObjectKey,
        String lightingImageUrl,
        UUID cameraPlanImageAssetId,
        String cameraPlanObjectKey,
        String cameraPlanImageUrl,
        String screenType,
        Integer renderWidth,
        Integer renderHeight,
        Map<String, Object> storyboardTag,
        Map<String, Object> lightingBuildSheetTag,
        Map<String, Object> cameraPlanSheetTag,
        Map<String, Object> rawShot
) {
}
