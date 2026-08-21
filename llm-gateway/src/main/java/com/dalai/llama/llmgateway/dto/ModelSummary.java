package com.dalai.llama.llmgateway.dto;

public record ModelSummary(
        String modelId,
        String type,
        String capabilities,
        Integer contextWindow,
        Boolean supportsStreaming
) {
}
