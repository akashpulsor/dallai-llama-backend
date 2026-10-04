package com.dalai.llama.postprod.dto;

import java.util.List;
import java.util.UUID;

/** The frames taken from the cut a shot currently uses, in order. */
public record ShotFrameExtractionResult(
        UUID shotId,
        UUID clipVersionId,
        String mode,
        int frameCount,
        List<ShotFrameView> frames
) {
}
