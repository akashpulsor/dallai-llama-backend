package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LockedIdeaSelectionResponse(
        UUID ideaId,
        UUID projectId,
        UUID trendId,
        String source,
        String title,
        String summary,
        Integer durationSeconds,
        String status,
        OffsetDateTime lockedAt,
        Map<String, Object> selectionContext
) {
}
