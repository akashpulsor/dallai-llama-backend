package com.dalai.llama.creator.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record TrendPredictionItemResponse(
        UUID trendId,
        String title,
        String summary,
        BigDecimal confidenceScore,
        String rationale,
        String evidenceType,
        List<String> suggestedTags
) {
}
