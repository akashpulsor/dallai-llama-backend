package com.dalai.llama.creator.dto.response;

import java.util.UUID;

public record ShortUploadPartUrlResponse(
        UUID uploadId,
        UUID videoId,
        Integer partNumber,
        String method,
        String uploadUrl,
        Integer expiresSeconds,
        Long startByte,
        Long endByte,
        String message
) {
}
