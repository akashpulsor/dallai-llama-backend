package com.dalai.llama.videogen.dto.generationplan;

/** A deterministic finding. {@code actionId} is null when the issue is not about one action. */
public record ValidationIssueView(
        String code,
        String actionId,
        String message
) {
}
