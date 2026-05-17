package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GeneratedIdeaResponse(
        UUID id,
        UUID lockedIdeaId,
        String title,
        String description,
        String source,
        Integer durationSeconds,
        List<String> hashtags,
        Map<String, Object> creativeNotes,
        OffsetDateTime createdAt
) {
}
