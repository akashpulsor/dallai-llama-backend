package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record StoryboardResponse(
        UUID storyboardId,
        UUID scriptId,
        UUID projectId,
        UUID ideaId,
        String title,
        String screenType,
        Integer renderWidth,
        Integer renderHeight,
        Integer durationSeconds,
        Integer totalShots,
        String status,
        List<StoryboardSceneResponse> scenes,
        OffsetDateTime createdAt
) {
}
