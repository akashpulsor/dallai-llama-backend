package com.dalai.llama.creator.dto.response;

import java.util.UUID;

public record ShortMultipartUploadSessionResponse(
        UUID uploadId,
        UUID videoId,
        String status,
        String bucket,
        String objectKey,
        String storageUploadId,
        Integer totalParts,
        Long partSizeBytes,
        Long maxPartSizeBytes,
        Long sizeBytes,
        Integer presignExpiresSeconds,
        String message
) {
}
