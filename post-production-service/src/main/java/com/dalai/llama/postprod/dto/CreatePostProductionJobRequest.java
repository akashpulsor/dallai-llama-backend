package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.PostProductionScope;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** scope=PROJECT: every completed shot in the project gets its own job (shotRef ignored).
 * scope=SHOT: shotRef is required, exactly one job is created for that shot regardless of
 * whether its siblings are done -- both explicitly requested modes: move the whole finished
 * video over at once, or work a single shot on its own. */
public record CreatePostProductionJobRequest(
        @NotNull PostProductionScope scope,
        @NotNull UUID projectId,
        String shotRef,
        /** Null/blank = same as source (lip-sync only, no dub). */
        String targetLanguage,
        /** Per-request model overrides -- null/blank falls back to each service's own configured
         * default. Lets different fal.ai-hosted models be tried per call to see which produces
         * the best result, without a redeploy. */
        String voiceCloneModel,
        String ttsModel,
        String lipSyncModel
) {
}
