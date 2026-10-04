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
        String lastError,
        /** Seconds asked of the model, and the shot's planned length. They differ when the clip is
         * generated shorter (or at a model's minimum) and conformed afterwards. */
        Integer durationSeconds,
        Integer plannedDurationSeconds,
        /** post-production's conform request for this clip -- poll it to see the planned-length cut. */
        UUID conformRequestId
) {
}
