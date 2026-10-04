package com.dalai.llama.videogen.dto.generationplan;

import java.util.List;

/**
 * The result of checking an edited prompt. {@code errors} are deterministic and block generation;
 * {@code warnings} are deterministic and do not. {@code aiFindings} are the AI review's possible
 * inconsistencies -- they never block, and an empty list is no guarantee the video will be right.
 * {@code aiReviewError} says why the AI review could not run, when it could not.
 */
public record PromptValidationView(
        int characterCount,
        int maxChars,
        List<ValidationIssueView> errors,
        List<ValidationIssueView> warnings,
        List<PromptReviewFindingView> aiFindings,
        String aiReviewError
) {
}
