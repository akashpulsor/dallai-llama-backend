package com.dalai.llama.creator.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreatorTrendResponse(
        UUID id,
        String platform,
        String category,
        String country,
        String title,
        String summary,
        String sourceName,
        String sourceUrl,
        BigDecimal score,
        BigDecimal velocity,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt
) {
}
