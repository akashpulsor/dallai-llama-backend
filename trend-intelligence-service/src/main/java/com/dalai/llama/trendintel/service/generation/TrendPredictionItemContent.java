package com.dalai.llama.trendintel.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/** One item of TREND_INTELLIGENCE_REPORT's {@code predictions[]} -- same field shape as
 * creator-service's TREND_PREDICT prompt output, copied deliberately (see {@code
 * TrendPredictionItem}'s javadoc). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrendPredictionItemContent(
        String title,
        String summary,
        BigDecimal confidenceScore,
        String rationale,
        String evidenceType,
        List<String> suggestedTags
) {
}
