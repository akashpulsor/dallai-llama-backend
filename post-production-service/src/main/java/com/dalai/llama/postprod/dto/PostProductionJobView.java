package com.dalai.llama.postprod.dto;

import java.util.UUID;

public record PostProductionJobView(
        UUID jobId,
        UUID projectId,
        String shotRef,
        String scope,
        String status,
        UUID videoGenJobId,
        String lastError
) {
}
