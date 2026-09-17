package com.dalai.llama.videogen.domain;

public enum JobStatus {
    PENDING_APPROVAL,
    /**
     * Approved and waiting its turn on the generation topic. Nothing has been sent to a provider
     * yet and nothing is being billed.
     *
     * <p>Distinct from {@link #PROCESSING} on purpose. Stale-job reconciliation treats a PROCESSING
     * row older than its threshold as a pod that died mid-dispatch and gives up on it -- correct
     * for a render that really started, and completely wrong for a shot sitting in a queue behind
     * a busy tenant, which would be abandoned for the crime of waiting. The reaper looks only at
     * PROCESSING, so a queue can be as long as it needs to be.
     */
    QUEUED,
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
        return this != PENDING_APPROVAL && this != QUEUED && this != PROCESSING;
    }

    /** True while the shot is on its way to becoming a clip -- queued or rendering. What a page
     * polling for an answer should keep waiting through. */
    public boolean isInFlight() {
        return this == QUEUED || this == PROCESSING;
    }
}
