package com.dalai.llama.tenant.youtube.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One of the creator's imported videos, with whether it can be showcased and why not. */
public record YouTubeVideoView(
        String videoId,
        String title,
        String thumbnailUrl,
        OffsetDateTime publishedAt,
        BigDecimal durationSeconds,
        int aspectW,
        int aspectH,
        boolean vertical,
        boolean eligible,
        /* Null when eligible: NOT_PUBLIC, EMBEDDING_OFF, AGE_RESTRICTED, MADE_FOR_KIDS, TOO_SHORT, TOO_LONG. */
        String ineligibleReason,
        String watchUrl
) {
}
