package com.dalai.llama.creator.service;

import com.dalai.llama.creator.ai.CreatorAiProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CreatorAiService {

    private final CreatorAiProvider creatorAiProvider;

    public CreatorAiService(CreatorAiProvider creatorAiProvider) {
        this.creatorAiProvider = creatorAiProvider;
    }

    public Map<String, Object> generate(String promptType, Map<String, Object> input) {
        return creatorAiProvider.generate(promptType, input);
    }

    public String providerName() {
        return creatorAiProvider.providerName();
    }
}
