package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PostProductionProjectResponse(
        UUID projectId,
        UUID scriptId,
        UUID storyIdeaId,
        String title,
        String status,
        Integer durationSeconds,
        String screenType,
        Integer shotCount,
        List<PostProductionShotResponse> shots,
        OffsetDateTime updatedAt
) {
}
