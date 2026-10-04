package com.dalai.llama.videogen.dto.generationplan;

import java.math.BigDecimal;

/** The assessment's claimed timing for one required action. */
public record ActionCoverageView(
        String actionId,
        BigDecimal startSeconds,
        BigDecimal endSeconds,
        boolean preserved
) {
}
