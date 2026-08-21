package com.dalai.llama.joblifecycle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared job-lifecycle transitions (mark-processing / finish-success / finish-failure /
 * reclaim-stale) that every async-job service in this system was re-implementing by hand.
 * Concrete services supply their own repository and their own not-found exception via
 * {@link #notFound(UUID)} (Template Method) so callers keep seeing the exception type they
 * already handle everywhere else, not a foreign one from this module.
 */
public abstract class AbstractJobLifecycleService<T extends TrackedJob> {

    protected final JpaRepository<T, UUID> repository;

    protected AbstractJobLifecycleService(JpaRepository<T, UUID> repository) {
        this.repository = repository;
    }

    protected abstract RuntimeException notFound(UUID jobId);

    @Transactional
    public T markProcessing(UUID jobId) {
        T job = requireJob(jobId);
        job.setStatus(JobLifecycleStatus.PROCESSING);
        job.setProcessingStartedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    /**
     * @param successMutator applies the job's own result fields (output URI, generated content,
     *                        etc.) before this method stamps the shared status/completedAt columns.
     */
    @Transactional
    public T finishSuccess(UUID jobId, Consumer<T> successMutator) {
        T job = requireJob(jobId);
        successMutator.accept(job);
        job.setStatus(JobLifecycleStatus.COMPLETED);
        job.setCompletedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    @Transactional
    public T finishFailure(UUID jobId, String errorMessage) {
        T job = requireJob(jobId);
        job.setStatus(JobLifecycleStatus.FAILED);
        job.setLastError(errorMessage);
        job.setCompletedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    /**
     * Declares a job dead only if it is still {@code PROCESSING} -- a no-op if it already
     * completed/failed on its own between the staleness scan and this call.
     */
    @Transactional
    public Optional<T> reclaimIfStillProcessing(UUID jobId, String reason) {
        T job = requireJob(jobId);
        if (job.getStatus() != JobLifecycleStatus.PROCESSING) {
            return Optional.empty();
        }
        job.setStatus(JobLifecycleStatus.TIMED_OUT);
        job.setLastError(reason);
        job.setCompletedAt(OffsetDateTime.now());
        return Optional.of(repository.save(job));
    }

    protected T requireJob(UUID jobId) {
        return repository.findById(jobId).orElseThrow(() -> notFound(jobId));
    }
}
