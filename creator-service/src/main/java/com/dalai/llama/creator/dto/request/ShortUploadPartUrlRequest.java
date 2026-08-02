package com.dalai.llama.creator.dto.request;

import java.util.UUID;

public record ShortUploadPartUrlRequest(
        UUID videoId,
        String storageUploadId,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        Integer totalParts,
        Long partSizeBytes
) {
}
