package com.dalai.llama.videogen.dto.generationplan;

import java.util.UUID;

/** The previous shot's last frame, attached so this clip opens where that one ended. */
public record ContinuationFrameView(
        UUID sourceShotId,
        String objectKey,
        Long timestampMs,
        String imageUrl
) {
}
