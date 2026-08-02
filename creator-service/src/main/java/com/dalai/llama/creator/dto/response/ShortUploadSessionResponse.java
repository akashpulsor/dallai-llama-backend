package com.dalai.llama.creator.dto.response;

import java.util.List;
import java.util.UUID;

public record ShortUploadSessionResponse(
        UUID uploadId,
        String status,
        Integer chunkIndex,
        Integer totalChunks,
        Long chunkSizeBytes,
        Long maxChunkSizeBytes,
        Long receivedBytes,
        List<Integer> missingChunks,
        String message
) {
}