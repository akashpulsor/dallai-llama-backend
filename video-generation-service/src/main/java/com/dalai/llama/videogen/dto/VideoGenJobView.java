package com.dalai.llama.videogen.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record VideoGenJobView(
        UUID jobId,
        String shotRef,
        String status,
        String approvalStatus,
        String outputUri,
        BigDecimal estimatedCost,
        BigDecimal actualCost
) {
}
