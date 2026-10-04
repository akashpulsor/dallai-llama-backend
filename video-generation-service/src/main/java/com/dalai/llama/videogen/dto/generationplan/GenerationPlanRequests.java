package com.dalai.llama.videogen.dto.generationplan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

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

    /** {@code sourceShotId} null means the shot immediately before this one. */
    public record AttachContinuationFrame(UUID sourceShotId) {
    }
}
