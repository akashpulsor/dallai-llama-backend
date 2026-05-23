package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ShotProductionPlanTagResponse(
        UUID planId,
        Integer shotNumber,
        String styleKey,
        Map<String, Object> storyboardTag,
        Map<String, Object> lightingBuildSheetTag,
        Map<String, Object> cameraPlanSheetTag,
        Map<String, Object> promptRunIds,
        Map<String, Object> rawPromptResponses,
        OffsetDateTime generatedAt
) {
}
