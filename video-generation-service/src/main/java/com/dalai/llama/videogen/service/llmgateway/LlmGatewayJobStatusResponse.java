package com.dalai.llama.videogen.service.llmgateway;

import java.util.UUID;

public record LlmGatewayJobStatusResponse(
        UUID jobId,
        String status,
        String error
) {
}
