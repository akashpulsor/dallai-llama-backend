package com.dalai.llama.trendintel.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The shape TREND_INTELLIGENCE_REPORT returns -- {@code predictions[]}, matching
 * creator-service's own TREND_PREDICT output contract exactly ({@code
 * aiOutput.get("predictions")} in {@code TrendPredictionService}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrendReportContent(
        List<TrendPredictionItemContent> predictions
) {
}
