package com.dalai.llama.creativeplanning.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BrandContextVersionView(
        UUID id,
        UUID brandContextId,
        Integer version,
        String brandName,
        String industry,
        String brandVoice,
        String targetAudience,
        String brandValues,
        OffsetDateTime createdAt
) {
}
