package com.dalai.llama.creativeplanning.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BrandContextView(
        UUID id,
        String brandName,
        String industry,
        String brandVoice,
        String targetAudience,
        String brandValues,
        OffsetDateTime createdAt,
        Integer currentVersion
) {
}
