package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ShortGenerationResponse(
        UUID videoId,
        UUID generationJobId,
        UUID sourceAssetId,
        String status,
        String title,
        String platform,
        Integer targetDurationSeconds,
        Integer requestedShorts,
        String reviewMode,
        Map<String, Object> sourceAsset,
        Map<String, Object> settings,
        Map<String, Object> videoDna,
        List<Map<String, Object>> transcript,
        Map<String, Object> graph,
        List<Map<String, Object>> trace,
        List<ShortCandidateResponse> candidates,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt
) {
}