package com.dalai.llama.llmgateway.dto;

import java.util.UUID;

public record JobStatusResponse(
        UUID jobId,
        String status,
        String response,
        String error,
        UsageDto usage
) {
}
