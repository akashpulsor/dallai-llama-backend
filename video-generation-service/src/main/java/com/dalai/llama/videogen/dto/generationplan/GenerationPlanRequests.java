package com.dalai.llama.videogen.dto.generationplan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Request bodies for the video studio's endpoints. */
public final class GenerationPlanRequests {

    private GenerationPlanRequests() {
    }

    /** {@code generationFps} may be null only for a model whose frame rate is not declared. */
    public record SelectSettings(@NotNull @Positive Integer generationDurationSeconds, @Positive Integer generationFps) {
    }

    /** {@code expectedRevision} is the draftRevision the edit was made on. */
    public record SaveDraft(@NotBlank String prompt, @NotNull Integer expectedRevision) {
    }

    public record ValidatePrompt(@NotBlank String prompt) {
    }

    /** {@code prompt} is submitted exactly as sent. */
    public record Generate(@NotBlank String prompt) {
    }

    /** The timeline as the creator corrected it, every interval in order. */
    public record EditTimeline(@NotEmpty List<@Valid TimelineInterval> intervals) {
    }

    public record TimelineInterval(@NotNull BigDecimal startSeconds, @NotNull BigDecimal endSeconds,
                                   @NotBlank String actionId, String action, String subjectState,
                                   String cameraBehavior, Boolean holdRequired) {
    }

    /** {@code sourceShotId} null means the shot immediately before this one. */
    public record AttachContinuationFrame(UUID sourceShotId) {
    }
}
