package com.dalai.llama.videogen.domain;

/** Lifecycle of a prepare-batch. PENDING is "accepted and published, not yet picked up"; RUNNING
 * means a consumer has it. Both count as live, and the partial unique index on prepare_batch_job
 * uses exactly these two to refuse a second batch for the same project. */
public enum PrepareBatchJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isLive() {
        return this == PENDING || this == RUNNING;
    }
}
