package com.dalai.llama.creator.ai;

import java.util.Map;

public interface CreatorAiProvider {

    String providerName();

    Map<String, Object> generate(String promptType, Map<String, Object> input);
}
