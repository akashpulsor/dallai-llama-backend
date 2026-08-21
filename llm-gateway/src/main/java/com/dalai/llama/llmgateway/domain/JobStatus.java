package com.dalai.llama.llmgateway.domain;

/**
 * llm_job.status values. Kept as a real enum (persisted via {@code name()}) rather than
 * scattered string literals so the state machine in {@code LlmGatewayService} is exhaustive
 * and typo-proof.
 */
public enum JobStatus {
    /** Dispatched to a provider and awaiting a result -- either genuinely in flight on this
     * pod, or a crash left it stranded (see {@code LlmGatewayService#isStale}). */
    PROCESSING,
    COMPLETED,
    /** Provider call failed (see {@link com.dalai.llama.llmgateway.service.provider.LlmProviderException}). Same idempotency key may retry. */
    FAILED,
    /** User-cancelled via {@code POST /v1/jobs/{id}/cancel}. Same idempotency key may retry. */
    CANCELLED,
    /** Self-healed from a stale PROCESSING row (crash recovery) -- no live provider call could
     * be found to cancel, so the job is presumed dead. Same idempotency key may retry. */
    TIMED_OUT;

    public boolean isTerminal() {
        return this != PROCESSING;
    }

    /** Terminal states that a retry with the same idempotency key is allowed to redispatch from. */
    public boolean isRetryable() {
        return this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
