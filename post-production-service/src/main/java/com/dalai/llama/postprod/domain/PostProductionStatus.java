package com.dalai.llama.postprod.domain;

/** Shared status vocabulary for both PostProductionJob and DialogueSyncJob -- same lifecycle
 * shape as video-generation-service's own JobStatus, including the TIMED_OUT lesson learned
 * there (a stranded PROCESSING row needs a distinct terminal state from an actual dispatch
 * FAILED, so a reconciliation task can tell "gave up waiting" apart from "provider said no"). */
public enum PostProductionStatus {
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
