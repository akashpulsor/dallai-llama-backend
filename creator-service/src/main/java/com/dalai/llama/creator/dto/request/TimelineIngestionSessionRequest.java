package com.dalai.llama.creator.dto.request;

import java.util.UUID;

public record TimelineIngestionSessionRequest(
        UUID projectId,
        String title,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        Integer expectedParts,
        Double partDurationSeconds,
        Double sceneWindowSeconds,
        Double thumbnailIntervalSeconds,
        String platform
) {
}
