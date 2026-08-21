package com.dalai.llama.trendintel.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TrendReportView(
        UUID id,
        String topic,
        String industry,
        String targetAudience,
        List<TrendPredictionItemView> predictions,
        OffsetDateTime createdAt
) {
}
