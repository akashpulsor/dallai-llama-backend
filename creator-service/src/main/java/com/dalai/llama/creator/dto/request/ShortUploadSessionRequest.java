package com.dalai.llama.creator.dto.request;

public record ShortUploadSessionRequest(
        String originalFilename,
        String contentType,
        Long sizeBytes,
        Integer totalChunks,
        Long chunkSizeBytes
) {
}