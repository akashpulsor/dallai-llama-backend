package com.dalai.llama.preprod.domain;

public enum ShotStatus {
    DRAFT,
    READY,
    GENERATING,
    /** Dispatched with {@code autoApprove=false} -- video-generation-service built the prompt and
     * held it for review (its own {@code PENDING_APPROVAL}/{@code ApprovalStatus.PENDING}), no
     * video generated yet. The creator reviews the prompt/model/cost, then approves or rejects it
     * directly against video-generation-service's own job endpoints. */
    PENDING_APPROVAL,
    GENERATED,
    NEEDS_REGENERATION,
    /** The pre-flight critic harness returned NEEDS_HUMAN_REVIEW -- a P1 finding survived the
     * bounded revision pass. Never auto-dispatched from here; a human must review the findings
     * (via {@code GenerationJob}-less {@code GET /v1/shots/{shotId}/thoughts} and the critique
     * session on critic-service) and either fix the shot plan or re-trigger generate(). */
    NEEDS_REVIEW
}
