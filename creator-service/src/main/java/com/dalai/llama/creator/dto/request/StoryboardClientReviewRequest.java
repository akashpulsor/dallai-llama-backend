package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record StoryboardClientReviewRequest(
        @Size(max = 6000)
        String storyboardFeedback,
        @Size(max = 6000)
        String productionFramesFeedback,
        @Size(max = 6000)
        String dialogueFeedback,
        @Size(max = 64)
        String dialogueLanguage,
        @Pattern(
                regexp = "(?i)DRAFT|CHANGES_REQUESTED|READY_FOR_CLIENT|APPROVED",
                message = "reviewStatus must be DRAFT, CHANGES_REQUESTED, READY_FOR_CLIENT, or APPROVED"
        )
        String reviewStatus,
        @Size(max = 100)
        List<Map<String, Object>> frameFeedback,
        @Size(max = 30)
        List<String> referenceUrls,
        @Size(max = 40)
        List<Map<String, Object>> visualReferenceImages,
        @Size(max = 12)
        List<Map<String, Object>> fontReferenceImages,
        @Size(max = 200)
        List<Map<String, Object>> reviewChat,
        Map<String, Object> typographySystem,
        @Size(max = 200)
        List<Map<String, Object>> overlayPlan,
        Map<String, Object> videoDirectorPlan,
        @Size(max = 120)
        String selectedReviewMessageId
) {
    public StoryboardClientReviewRequest(
            String storyboardFeedback,
            String productionFramesFeedback,
            String dialogueFeedback,
            String dialogueLanguage,
            String reviewStatus,
            List<Map<String, Object>> frameFeedback,
            List<String> referenceUrls,
            Map<String, Object> typographySystem,
            List<Map<String, Object>> overlayPlan
    ) {
        this(storyboardFeedback, productionFramesFeedback, dialogueFeedback, dialogueLanguage, reviewStatus,
                frameFeedback, referenceUrls, List.of(), List.of(), List.of(), typographySystem, overlayPlan, Map.of(), null);
    }

    public StoryboardClientReviewRequest(
            String storyboardFeedback,
            String productionFramesFeedback,
            String dialogueFeedback,
            String dialogueLanguage,
            String reviewStatus,
            List<Map<String, Object>> frameFeedback,
            List<String> referenceUrls,
            List<Map<String, Object>> fontReferenceImages,
            Map<String, Object> typographySystem,
            List<Map<String, Object>> overlayPlan
    ) {
        this(storyboardFeedback, productionFramesFeedback, dialogueFeedback, dialogueLanguage, reviewStatus,
                frameFeedback, referenceUrls, List.of(), fontReferenceImages, List.of(), typographySystem, overlayPlan, Map.of(), null);
    }

    public StoryboardClientReviewRequest(
            String storyboardFeedback,
            String productionFramesFeedback,
            String dialogueFeedback,
            String dialogueLanguage,
            String reviewStatus,
            List<Map<String, Object>> frameFeedback,
            List<String> referenceUrls,
            List<Map<String, Object>> fontReferenceImages,
            List<Map<String, Object>> reviewChat,
            Map<String, Object> typographySystem,
            List<Map<String, Object>> overlayPlan
    ) {
        this(storyboardFeedback, productionFramesFeedback, dialogueFeedback, dialogueLanguage, reviewStatus,
                frameFeedback, referenceUrls, List.of(), fontReferenceImages, reviewChat, typographySystem, overlayPlan, Map.of(), null);
    }

    public StoryboardClientReviewRequest(
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
            Map<String, Object> videoDirectorPlan
    ) {
        this(storyboardFeedback, productionFramesFeedback, dialogueFeedback, dialogueLanguage, reviewStatus,
                frameFeedback, referenceUrls, visualReferenceImages, fontReferenceImages, reviewChat,
                typographySystem, overlayPlan, videoDirectorPlan, null);
    }

    public StoryboardClientReviewRequest(
            String storyboardFeedback,
            String productionFramesFeedback,
            String dialogueFeedback,
            String dialogueLanguage,
            String reviewStatus
    ) {
        this(storyboardFeedback, productionFramesFeedback, dialogueFeedback, dialogueLanguage, reviewStatus,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), Map.of(), null);
    }
}
