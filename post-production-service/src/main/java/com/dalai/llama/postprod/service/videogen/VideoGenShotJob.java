package com.dalai.llama.postprod.service.videogen;

import java.util.UUID;

/** Mirrors video-generation-service's own VideoGenJobView -- only the fields post-production
 * actually needs. */
public record VideoGenShotJob(
        UUID jobId,
        String shotRef,
        String status,
        String approvalStatus
) {
    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status);
    }
}
