package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record LockIdeaSelectionRequest(
        @NotBlank String sourceType,
        UUID projectId,
        UUID trendId,
        String platformCode,
        String categoryCode,
        String countryCode,
        String timeframe,
        @NotBlank @Size(max = 240) String ideaTitle,
        @NotBlank @Size(max = 4000) String ideaText,
        Integer durationSeconds,
        Map<String, Object> selectionPayload
) {
}
