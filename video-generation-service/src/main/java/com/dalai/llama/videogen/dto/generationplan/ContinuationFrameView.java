package com.dalai.llama.videogen.dto.generationplan;

import java.util.UUID;

/**
 * The previous shot's last frame, attached so this clip opens where that one ended.
 * {@code status} is post-production's answer as last seen: READY (the frame is here), EXTRACTING
 * (being taken -- poll the plan) or FAILED ({@code error} says why, typically that the shot has no
 * video yet).
 */
public record ContinuationFrameView(
        UUID sourceShotId,
        String status,
        String objectKey,
        Long timestampMs,
        String imageUrl,
        String error
) {
}
