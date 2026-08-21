package com.dalai.llama.videogen.domain;

public enum JobStatus {
    PENDING_APPROVAL,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REJECTED,
    /** A PROCESSING row that sat past its staleness threshold with no terminal write -- presumed
     * crashed mid-dispatch (pod died), not genuinely still running. See
     * {@code StaleJobReconciliationTask}. */
    TIMED_OUT;

    public boolean isTerminal() {
        return this != PENDING_APPROVAL && this != PROCESSING;
    }
}
