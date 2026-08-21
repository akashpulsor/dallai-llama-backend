package com.dalai.llama.videogen.service.llmgateway;

public record LlmGatewayModelSummary(
        String modelId,
        String type,
        String capabilities,
        Integer contextWindow,
        Boolean supportsStreaming
) {
}
