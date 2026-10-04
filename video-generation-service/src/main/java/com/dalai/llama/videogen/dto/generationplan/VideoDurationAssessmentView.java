package com.dalai.llama.videogen.dto.generationplan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** The AI's advice on how long, and at what frame rate, to generate the shot. Advisory only. */
public record VideoDurationAssessmentView(
        Boolean shorterGenerationSuitable,
        BigDecimal minimumViableDurationSeconds,
        Integer recommendedDurationSeconds,
        Integer recommendedGenerationFps,
        String reasoning,
        List<ActionCoverageView> actionCoverage,
        List<String> risks,
        OffsetDateTime assessedAt
) {
}
