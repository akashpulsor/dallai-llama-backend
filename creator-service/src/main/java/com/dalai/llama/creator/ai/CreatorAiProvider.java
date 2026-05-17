package com.dalai.llama.creator.ai;

import java.util.Map;

public interface CreatorAiProvider {

    String providerName();

    default boolean supports(String providerCode) {
        return providerName().equalsIgnoreCase(providerCode);
    }

    Map<String, Object> generate(String promptType, Map<String, Object> input);
}
