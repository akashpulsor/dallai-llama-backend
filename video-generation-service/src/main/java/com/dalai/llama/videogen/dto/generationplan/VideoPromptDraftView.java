package com.dalai.llama.videogen.dto.generationplan;

import java.time.OffsetDateTime;

/**
 * The prompt in its two editable forms. {@code userEditedPrompt} is null until the creator saves an
 * edit; the page shows it when present and the AI recommendation otherwise. {@code draftRevision}
 * is echoed back on save so two tabs cannot silently overwrite each other.
 *
 * <p>{@code promptDurationSeconds}, {@code promptFps} and {@code promptContinuationObjectKey} record
 * what the recommendation was composed for; the page compares them with the current settings.
 */
public record VideoPromptDraftView(
        String aiRecommendedPrompt,
        String userEditedPrompt,
        int draftRevision,
        Integer promptDurationSeconds,
        Integer promptFps,
        String promptContinuationObjectKey,
        OffsetDateTime promptComposedAt,
        OffsetDateTime draftSavedAt,
        int maxChars
) {
}
