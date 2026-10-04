package com.dalai.llama.videogen.dto.generationplan;

/** A possible creative inconsistency the AI review raised. Advice, not a verdict. */
public record PromptReviewFindingView(
        String category,
        String severity,
        String actionId,
        String message
) {
}
