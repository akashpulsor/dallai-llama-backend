package com.dalai.llama.creativeplanning.domain;

/** Lifecycle for one row of {@code idea_generation_job}. Mirrors pre-production-service's
 * {@code ShotListJobStatus} shape (same terminal-vs-live distinction). */
public enum IdeaGenerationJobStatus {
    PENDING,
    SUCCEEDED,
    FAILED;

    public boolean isLive() {
        return this == PENDING;
    }
}
