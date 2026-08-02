package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record StoryboardClientReviewChatRequest(
        @Size(max = 120)
        String messageId,
        @NotBlank
        @Size(max = 24000)
        String message,
        @Pattern(
                regexp = "(?i)STORYBOARD|PRODUCT_FRAME|STORYBOARD_AND_PRODUCT|PLANNING",
                message = "targetType must be STORYBOARD, PRODUCT_FRAME, STORYBOARD_AND_PRODUCT, or PLANNING"
        )
        String targetType,
        @Min(1)
        Integer shotNumber,
        @Size(max = 64)
        String dialogueLanguage,
        Map<String, Object> currentReview,
        @Size(max = 8)
        List<@Size(max = 120) String> visualReferenceAssetIds,
        @Pattern(
                regexp = "(?i)INSPIRATION_ONLY|EXACT_SOURCE",
                message = "visualReferenceUsageMode must be INSPIRATION_ONLY or EXACT_SOURCE"
        )
        String visualReferenceUsageMode
) {
    public StoryboardClientReviewChatRequest(
            String messageId,
            String message,
            String targetType,
            Integer shotNumber,
            String dialogueLanguage,
            Map<String, Object> currentReview
    ) {
        this(messageId, message, targetType, shotNumber, dialogueLanguage, currentReview, List.of(), "INSPIRATION_ONLY");
    }

    public StoryboardClientReviewChatRequest(
            String messageId,
            String message,
            String targetType,
            Integer shotNumber,
            String dialogueLanguage,
            Map<String, Object> currentReview,
            List<String> visualReferenceAssetIds
    ) {
        this(messageId, message, targetType, shotNumber, dialogueLanguage, currentReview, visualReferenceAssetIds, "INSPIRATION_ONLY");
    }
}
