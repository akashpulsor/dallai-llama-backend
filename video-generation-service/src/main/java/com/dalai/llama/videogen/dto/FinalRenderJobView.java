package com.dalai.llama.videogen.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Poll-friendly view of a {@link com.dalai.llama.videogen.domain.entity.FinalRenderJob}.
 * {@code videoUrl} is the 302-target path (relative to the video-generation-service host)
 * the UI can hand to a {@code &lt;video src=""&gt;} tag without a second round-trip --
 * null until COMPLETED. */
public record FinalRenderJobView(
        UUID renderId,
        UUID projectId,
        String status,
        Integer shotCount,
        String videoUrl,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
}
