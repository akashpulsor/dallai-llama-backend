package com.dalai.llama.postprod.service.videogen;

import java.util.UUID;

/** Mirrors video-generation-service's own VideoGenJobView -- only the fields post-production
 * actually needs. */
public record VideoGenShotJob(
        UUID jobId,
        String shotRef,
        String status,
        String approvalStatus,
        boolean muteAudio,
        Boolean dubSucceeded
) {
    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    /** True when video-generation-service already muxed a beat-matched cloned-voice track onto
     * this shot -- DialogueSyncCoordinator uses this to skip straight to a quality check instead
     * of running the full clone/synthesize/lip-sync pipeline for the same-language case. */
    public boolean alreadyAutoDubbed() {
        return muteAudio && Boolean.TRUE.equals(dubSucceeded);
    }
}
