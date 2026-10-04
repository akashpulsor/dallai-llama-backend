package com.dalai.llama.postprod.dto;

import java.util.List;
import java.util.UUID;

/**
 * A frame extraction request and, once it has run, its frames in order. {@code status} is QUEUED or
 * PROCESSING while the consumer works, then COMPLETED or FAILED ({@code error} says why).
 * {@code requestId} is null when the frames were already stored for the shot's current cut and
 * nothing had to be queued.
 */
public record ShotFrameExtractionResult(
        UUID requestId,
        UUID shotId,
        String mode,
        String status,
        UUID clipVersionId,
        String error,
        int frameCount,
        List<ShotFrameView> frames
) {
}
