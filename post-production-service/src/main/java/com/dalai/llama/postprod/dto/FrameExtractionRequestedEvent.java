package com.dalai.llama.postprod.dto;

import java.util.UUID;

/** A frame extraction to work off the request thread. Everything it needs is on the
 * shot_frame_extraction row; the id is all that travels. */
public record FrameExtractionRequestedEvent(
        UUID requestId,
        UUID tenantId,
        UUID shotId
) {
}
