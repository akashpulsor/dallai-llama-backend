package com.dalai.llama.llmgateway.service.prompt;

import com.dalai.llama.llmgateway.dto.prompt.PromptDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seedance ({@code seedance-*}) takes the same structured line-per-directive shape as the
 * default composition. Delegates to {@link DefaultPromptStrategy}'s composition so the two
 * are guaranteed to stay in lockstep.
 */
@Component
public class SeedancePromptStrategy implements ProviderPromptStrategy {

    private final DefaultPromptStrategy defaultPromptStrategy;
    private final int maxPromptLength;

    public SeedancePromptStrategy(
            DefaultPromptStrategy defaultPromptStrategy,
            @Value("${llm-gateway.prompt-format.seedance-max-prompt-length:2000}") int maxPromptLength
    ) {
        this.defaultPromptStrategy = defaultPromptStrategy;
        this.maxPromptLength = maxPromptLength;
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null && modelId.startsWith("seedance");
    }

    @Override
    public int maxPromptLength() {
        return maxPromptLength;
    }

    @Override
    public Built build(PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        return defaultPromptStrategy.build(shotContext, flags);
    }
}
