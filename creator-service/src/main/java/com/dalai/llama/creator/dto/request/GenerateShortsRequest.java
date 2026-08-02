package com.dalai.llama.creator.dto.request;

import java.util.UUID;

public record GenerateShortsRequest(
        UUID projectId,
        String title,
        String platform,
        Integer targetDurationSeconds,
        Integer requestedShorts,
        String reviewMode,
        String creatorProfileJson,
        String notes,
        String executionMode
) {
}
