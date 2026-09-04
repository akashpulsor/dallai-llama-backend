package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;

public interface PromptBuilderService {

    /** Composes the positive+negative prompt using the {@link
     * com.dalai.llama.videogen.service.prompt.ProviderPromptStrategy} that matches {@code
     * modelId} -- null falls back to the default strategy. Prefer this over the modelId-less
     * overload whenever the caller knows which model will run, so per-model composition shapes
     * (paragraph vs line-per-directive, etc.) actually apply. */
    BuiltPrompt buildPrompt(ShotContext shotContext, FeatureFlags effectiveFlags, String modelId);

    /** Model-specific documented prompt-length limit for the strategy matching {@code modelId}.
     * Used by {@link PromptCompressionService#compressIfNeeded} instead of the pre-strategy
     * single global default. Null modelId falls back to the default strategy's limit. */
    int maxPromptLengthFor(String modelId);

    /** Legacy no-modelId overload -- always resolves to the default strategy. Kept only so
     * pre-refactor callers don't have to be touched; new code should call the modelId-aware one. */
    default BuiltPrompt buildPrompt(ShotContext shotContext, FeatureFlags effectiveFlags) {
        return buildPrompt(shotContext, effectiveFlags, null);
    }
}
