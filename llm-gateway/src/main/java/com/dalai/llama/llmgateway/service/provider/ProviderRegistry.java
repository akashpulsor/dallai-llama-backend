package com.dalai.llama.llmgateway.service.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ProviderRegistry {

    private final Map<String, LlmProvider> providersById;

    public ProviderRegistry(List<LlmProvider> providers) {
        this.providersById = providers.stream()
                .collect(Collectors.toMap(LlmProvider::providerId, Function.identity()));
    }

    public LlmProvider resolve(String providerId) {
        LlmProvider provider = providersById.get(providerId);
        if (provider == null) {
            throw new LlmProviderException("No adapter registered for provider_id=" + providerId, false);
        }
        return provider;
    }
}
