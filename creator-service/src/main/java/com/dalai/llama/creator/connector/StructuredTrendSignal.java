package com.dalai.llama.creator.connector;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record StructuredTrendSignal(
        String sourceUrl,
        String title,
        String summary,
        String signalText,
        String sourceAuthor,
        BigDecimal engagementScore,
        BigDecimal rankScore,
        OffsetDateTime publishedAt,
        OffsetDateTime observedAt,
        Map<String, Object> rawItem,
        Map<String, Object> normalizedPayload,
        String dedupeKey
) {
}
