package com.dalai.llama.llmgateway.service.prompt;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Picks the {@link ProviderPromptStrategy} matching a given modelId. Two defensive layers so a
 * wrong ordering annotation on a new strategy in future doesn't silently short-circuit the
 * fallback: {@link DefaultPromptStrategy} is @Order(LOWEST_PRECEDENCE) AND explicitly skipped
 * during primary iteration.
 */
@Component
public class ProviderPromptStrategyResolver {

    private final List<ProviderPromptStrategy> strategies;
    private final DefaultPromptStrategy fallback;

    public ProviderPromptStrategyResolver(List<ProviderPromptStrategy> strategies, DefaultPromptStrategy fallback) {
        this.strategies = strategies;
        this.fallback = fallback;
    }

    public ProviderPromptStrategy resolve(String modelId) {
        for (ProviderPromptStrategy strategy : strategies) {
            if (strategy == fallback) continue;
            if (strategy.supports(modelId)) return strategy;
        }
        return fallback;
    }
}
