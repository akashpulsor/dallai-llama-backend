package com.dalai.llama.preprod.domain;

public enum ShotStatus {
    DRAFT,
    READY,
    GENERATING,
    GENERATED,
    NEEDS_REGENERATION,
    /** The pre-flight critic harness returned NEEDS_HUMAN_REVIEW -- a P1 finding survived the
     * bounded revision pass. Never auto-dispatched from here; a human must review the findings
     * (via {@code GenerationJob}-less {@code GET /v1/shots/{shotId}/thoughts} and the critique
     * session on critic-service) and either fix the shot plan or re-trigger generate(). */
    NEEDS_REVIEW
}
