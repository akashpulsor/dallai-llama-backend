package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.FrameExtractionMode;

import java.util.UUID;

/** Asks for frames of a shot's current cut off the request thread -- the way to extract every
 * frame of a long clip without holding a request open. {@code sampleFps} is read for SAMPLE only. */
public record FrameExtractionRequestedEvent(
        UUID tenantId,
        UUID projectId,
        UUID shotId,
        FrameExtractionMode mode,
        Integer sampleFps
) {
}
