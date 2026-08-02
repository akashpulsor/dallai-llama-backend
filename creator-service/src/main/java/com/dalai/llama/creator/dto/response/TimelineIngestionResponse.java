package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TimelineIngestionResponse(
        UUID videoId,
        UUID uploadId,
        String status,
        String title,
        Integer expectedParts,
        Integer receivedParts,
        Integer processedParts,
        Map<String, Object> masterVideo,
        Map<String, Object> timelineProject,
        List<Map<String, Object>> sourceParts,
        List<Map<String, Object>> trace,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
