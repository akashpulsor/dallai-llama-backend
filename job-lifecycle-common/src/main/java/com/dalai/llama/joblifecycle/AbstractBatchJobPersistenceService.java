package com.dalai.llama.joblifecycle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** markProcessing/finishSuccess/finishFailure come from {@link AbstractJobLifecycleService}
 * (same base every TrackedJob in this system already uses); this adds the progress/retry/DLQ
 * transitions specific to a {@link BatchJobRecord}. A concrete service subclasses this with its
 * own {@code JpaRepository<J, UUID>} and its own {@link BatchDeadLetterSink} (backed by whatever
 * dead-letter table that service keeps), inheriting everything else -- same "own the schema,
 * share the algorithm" split {@link AbstractJobLifecycleService} already established.
 * <p>
 * Every method here is a real transactional boundary on a real bean -- {@link AbstractBatchWorker}
 * calls these across a genuine bean boundary, never via {@code this.} on itself, so {@code
 * @Transactional} actually applies (the self-invocation pitfall called out throughout this
 * system's own job-persistence services). */
public abstract class AbstractBatchJobPersistenceService<J extends BatchJobRecord> extends AbstractJobLifecycleService<J> {

    private final JpaRepository<J, UUID> repository;
    private final BatchDeadLetterSink<?> deadLetterSink;

    protected AbstractBatchJobPersistenceService(JpaRepository<J, UUID> repository, BatchDeadLetterSink<?> deadLetterSink) {
        super(repository);
        this.repository = repository;
        this.deadLetterSink = deadLetterSink;
    }

    @Transactional
    public void recordProgress(UUID jobId, String currentStepLabel) {
        J job = requireJob(jobId);
        job.setCurrentStepLabel(currentStepLabel);
        repository.save(job);
    }

    @Transactional
    public void recordAttempt(UUID jobId, int attempts) {
        J job = requireJob(jobId);
        job.setCurrentStepAttempt(attempts);
        repository.save(job);
    }

    @Transactional
    public void advance(UUID jobId, int currentStepIndex, int totalSteps) {
        J job = requireJob(jobId);
        applyAdvance(job, currentStepIndex, totalSteps);
        repository.save(job);
    }

    /** The 3rd-consecutive-failure path: writes the dead-letter row and advances the cursor past
     * the failed step in one transaction, so a crash between the two can never leave a dead
     * letter recorded while the cursor still points at (and would re-attempt) the same step. */
    @SuppressWarnings("unchecked")
    @Transactional
    public <S> void deadLetterAndAdvance(UUID jobId, UUID tenantId, S step, String stepKey, int attempts,
                                          String lastError, int currentStepIndex, int totalSteps) {
        J job = requireJob(jobId);
        ((BatchDeadLetterSink<S>) deadLetterSink).deadLetter(jobId, tenantId, job.getOwnerId(), step, stepKey, attempts, lastError);
        job.setErrorCount(job.getErrorCount() + 1);
        applyAdvance(job, currentStepIndex, totalSteps);
        repository.save(job);
    }

    private void applyAdvance(J job, int currentStepIndex, int totalSteps) {
        job.setCurrentStepAttempt(0);
        int next = currentStepIndex + 1;
        job.setCurrentStepIndex(next);
        job.setCompletedSteps(next);
        if (next >= totalSteps) {
            // AbstractBatchWorker checks completedSteps/totalSteps to decide when to
            // finishSuccess -- it reads the freshly-saved row on its next call, not this one.
        }
    }
}
