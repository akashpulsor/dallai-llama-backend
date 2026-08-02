package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record StoryboardClientReviewResponse(
        UUID scriptId,
        String storyboardFeedback,
        String productionFramesFeedback,
        String dialogueFeedback,
        String dialogueLanguage,
        String reviewStatus,
        List<Map<String, Object>> frameFeedback,
        List<String> referenceUrls,
        List<Map<String, Object>> visualReferenceImages,
        List<Map<String, Object>> fontReferenceImages,
        List<Map<String, Object>> reviewChat,
        Map<String, Object> typographySystem,
        List<Map<String, Object>> overlayPlan,
        Map<String, Object> videoDirectorPlan,
        Map<String, Object> propagation,
        Map<String, Object> creativeLearning,
        List<Map<String, Object>> updatedShots,
        OffsetDateTime updatedAt,
        String updatedBy
) {
}
