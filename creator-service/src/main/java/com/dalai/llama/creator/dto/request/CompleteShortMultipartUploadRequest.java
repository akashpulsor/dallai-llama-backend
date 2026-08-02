package com.dalai.llama.creator.dto.request;

import java.util.UUID;

public record CompleteShortMultipartUploadRequest(
        UUID videoId,
        String storageUploadId,
        UUID projectId,
        String title,
        String platform,
        Integer targetDurationSeconds,
        Integer requestedShorts,
        String reviewMode,
        String creatorProfileJson,
        String notes,
        String executionMode,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        Integer totalParts,
        Long partSizeBytes
) {
    public GenerateShortsRequest toGenerateShortsRequest() {
        return new GenerateShortsRequest(
                projectId,
                title,
                platform,
                targetDurationSeconds,
                requestedShorts,
                reviewMode,
                creatorProfileJson,
                notes,
                executionMode
        );
    }
}
