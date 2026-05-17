package com.dalai.llama.creator.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TrendInsightResponse(
        UUID trendId,
        UUID promptRunId,
        String title,
        String category,
        String platform,
        String country,
        String summary,
        List<String> whyItWorked,
        List<TrendPostingWindowResponse> bestTimes,
        List<String> creatorActions,
        BigDecimal confidenceScore,
        String evidenceType,
        Map<String, Object> postingStrategy,
        Map<String, Object> aiOutput,
        OffsetDateTime generatedAt
) {
}
