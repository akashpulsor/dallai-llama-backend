package com.dalai.llama.llmgateway.service.prompt;

import com.dalai.llama.llmgateway.dto.prompt.PromptDtos;

/**
 * Per-model-family prompt composition + length limit. Each concrete strategy owns the
 * composition shape (paragraph vs line-per-directive), the model's real documented max prompt
 * length, and the negative-prompt shape (via {@link NegativePromptComposer}, the shared helper
 * every strategy calls). Selected by {@link ProviderPromptStrategyResolver} at build time by
 * matching {@link #supports(String)} against the resolved modelId; the fallback
 * {@link DefaultPromptStrategy} always matches, so a model without a dedicated strategy still
 * produces a valid prompt.
 *
 * <p>Moved from video-generation-service so per-model prompt-shape knowledge lives with
 * llm-gateway's model catalog / routing. Video-gen sends shot data as JSON, llm-gateway returns
 * the formatted {@code {positive, negative}} plus the strategy's max length.
 */
public interface ProviderPromptStrategy {

    boolean supports(String modelId);

    int maxPromptLength();

    Built build(PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags effectiveFlags);

    /** Result of {@link #build} -- composed positive and negative prompt text. Internal shape;
     * the controller wraps this into a {@link PromptDtos.PromptFormatResponse} on the way out. */
    record Built(String positive, String negative) {}
}
