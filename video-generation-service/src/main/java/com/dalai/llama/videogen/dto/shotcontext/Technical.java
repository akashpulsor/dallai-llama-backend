package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.AspectRatio;
import jakarta.validation.constraints.Positive;

public record Technical(
        @Positive Integer durationSeconds,
        AspectRatio aspectRatio,
        /** Informational only -- llm-gateway resolves provider from model_master once a model is
         * chosen; not required for dispatch. */
        String targetProvider,
        /** Optional pin -- when set, {@code ModelRecommendationService} is skipped entirely
         * (doc §21.6, "Override & pinning"). */
        String targetModel
) {
}
