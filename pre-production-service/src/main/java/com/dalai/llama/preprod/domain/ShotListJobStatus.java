package com.dalai.llama.preprod.domain;

/**
 * Local job-lifecycle states for {@link com.dalai.llama.preprod.domain.entity.ShotListJob}.
 * Deliberately not the shared {@code JobLifecycleStatus} enum -- this job's lifecycle is only
 * three states (waiting, done, done-with-error), whereas the shared enum models the fuller
 * dispatch/reconcile/timeout state machine that {@link
 * com.dalai.llama.preprod.domain.entity.GenerationJob} needs. Keeping this narrow keeps the
 * status endpoint's contract crisp for the UI.
 */
public enum ShotListJobStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
