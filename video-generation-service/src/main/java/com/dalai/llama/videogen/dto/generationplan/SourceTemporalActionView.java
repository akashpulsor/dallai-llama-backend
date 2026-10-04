package com.dalai.llama.videogen.dto.generationplan;

import java.math.BigDecimal;

/** One interval of the second-by-second source timeline. */
public record SourceTemporalActionView(
        BigDecimal startSeconds,
        BigDecimal endSeconds,
        String actionId,
        String action,
        String subjectState,
        String cameraBehavior,
        boolean holdRequired
) {
}
