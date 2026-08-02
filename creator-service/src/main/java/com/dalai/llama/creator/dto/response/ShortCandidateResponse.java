package com.dalai.llama.creator.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ShortCandidateResponse(
        UUID candidateId,
        UUID videoId,
        UUID assetId,
        Integer rankIndex,
        String title,
        Integer durationSeconds,
        BigDecimal score,
        String hookType,
        String status,
        String reviewStatus,
        Map<String, Object> editDecisionList,
        Map<String, Object> captionPlan,
        Map<String, Object> renderManifest,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}