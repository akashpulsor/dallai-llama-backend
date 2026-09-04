package com.dalai.llama.joblifecycle;

import java.util.UUID;

/** A {@link TrackedJob} that represents a whole batch of steps rather than one unit of work --
 * "generate all shot assets for a project," "render every clip in a storyboard," etc. {@code
 * ownerId} is deliberately generic (a project, a storyboard, whatever the batch is "about") so
 * this isn't pre-production-specific: any service running a batch through {@link
 * AbstractBatchWorker} implements this on its own JPA entity, same as {@link TrackedJob} already
 * works. The step list itself is never persisted here -- {@link BatchStepExecutor#planSteps} is
 * recomputed fresh each tick from {@code currentStepIndex}, the same "don't snapshot what can be
 * recomputed" choice {@code TrackedJob}-based jobs already make elsewhere in this system. */
public interface BatchJobRecord extends TrackedJob {

    UUID getTenantId();

    UUID getOwnerId();

    Integer getTotalSteps();

    void setTotalSteps(Integer totalSteps);

    Integer getCompletedSteps();

    void setCompletedSteps(Integer completedSteps);

    Integer getCurrentStepIndex();

    void setCurrentStepIndex(Integer currentStepIndex);

    /** Consecutive failures on the step at {@code currentStepIndex} -- resets to 0 the moment
     * that step succeeds or exhausts {@code AbstractBatchWorker.maxAttempts} and gets
     * dead-lettered. Never applies to a step that already succeeded. */
    Integer getCurrentStepAttempt();

    void setCurrentStepAttempt(Integer currentStepAttempt);

    /** Count of steps dead-lettered this run -- what a progress UI's "N issues" means. */
    Integer getErrorCount();

    void setErrorCount(Integer errorCount);

    /** e.g. "Shot 4 of 10 -- camera plan" -- whatever a progress UI displays while running. */
    String getCurrentStepLabel();

    void setCurrentStepLabel(String currentStepLabel);
}
