package com.dalai.llama.trendintel.dto;

import java.math.BigDecimal;
import java.util.List;

public record TrendPredictionItemView(
        String title,
        String summary,
        BigDecimal confidenceScore,
        String rationale,
        String evidenceType,
        List<String> suggestedTags
) {
}
