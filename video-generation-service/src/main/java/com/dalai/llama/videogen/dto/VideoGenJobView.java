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
        BigDecimal actualCost,
        boolean muteAudio,
        Boolean dubSucceeded,
        /** Why a FAILED job failed, in the provider's or the gateway's own words -- e.g.
         *  "Insufficient wallet balance. currentBalance=0 minimumRequired=50". The reason was
         *  always recorded on the job; it just had nowhere to travel, so every failure reached
         *  the caller as a bare FAILED and got reported as a generic "generation failed". Null
         *  for a job that has not failed. */
        String lastError
) {
}
