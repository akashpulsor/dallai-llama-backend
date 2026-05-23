package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record GenerationJobResponse(
        UUID jobId,
        String jobType,
        String status,
        Integer progress,
        String message,
        Map<String, Object> inputPayload,
        Map<String, Object> result,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
