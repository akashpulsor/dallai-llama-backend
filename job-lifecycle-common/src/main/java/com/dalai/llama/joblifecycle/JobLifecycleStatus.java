package com.dalai.llama.joblifecycle;

/**
 * Shared status vocabulary for every durably-persisted async job in this system. Previously
 * reinvented per service with slightly different spellings (llm-gateway's JobStatus,
 * video-generation-service's own JobStatus, post-production-service's PostProductionStatus) --
 * this is the single definition new services build against (see the design doc's own §12).
 */
public enum JobLifecycleStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this != PENDING && this != PROCESSING;
    }
}
