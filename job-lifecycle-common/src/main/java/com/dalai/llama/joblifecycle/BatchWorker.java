package com.dalai.llama.joblifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** The one algorithm behind every "generate a batch of N things, one at a time, retry failures,
 * dead-letter what won't succeed" run in this system -- pulled out here so a service never has to
 * re-invent the queue/cursor/retry/DLQ machinery just because its business logic differs (see
 * this module's own doc for why {@code job-lifecycle-common} exists at all). A concrete service
 * owns exactly three things: its own {@link BatchJobRecord} entity/table, a {@link
 * BatchStepExecutor} that knows how to run and label its own steps, and one {@code @Scheduled}
 * method (Spring scheduling can't be triggered from a plain library class) whose body is just
 * {@code batchWorker.tick()}.
 * <p>
 * Runs one step per {@code tick()} call. In a Spring Boot app with {@code @EnableScheduling} and
 * no custom {@code TaskScheduler} bean, the default scheduling pool is exactly one thread, so
 * wiring {@code tick()} behind a single {@code @Scheduled} method gives a real FIFO queue drained
 * by one dedicated worker -- never a thread pool spun up per batch, never more than one step in
 * flight at a time for that service. {@code fixedDelay} scheduling counts from when a tick
 * RETURNS, so a step that itself blocks for seconds (a real generation call) naturally paces the
 * next tick -- no separate throttling needed on top.
 * <p>
 * A step that fails is retried in place (same cursor, {@link BatchJobRecord#getCurrentStepAttempt()}
 * incremented) up to {@code maxAttempts} times before being dead-lettered and the cursor moving
 * on -- never retried indefinitely, and never revisited automatically once dead-lettered (only a
 * direct, individual retry against that dead-letter row runs it again). A step that already
 * succeeded is never re-attempted: the cursor only ever moves forward.
 * <p>
 * Assumes the owning service runs as a single replica. Two pods would each run their own
 * independent single-threaded scheduler, so two workers could claim overlapping jobs -- the
 * {@code nextJobSupplier} passed into the constructor has no distributed claim/lock to prevent
 * that. Worth revisiting only if a service using this ever actually scales past one replica.
 * <p>
 * A plain composed helper, not a base class to extend -- there's no per-service behavior left to
 * override once {@link BatchStepExecutor} and {@link BatchJobRecord} are supplied, so a concrete
 * service just constructs one and calls {@link #tick()} from its own {@code @Scheduled} method. */
public class BatchWorker<J extends BatchJobRecord, S> {

    private static final Logger log = LoggerFactory.getLogger(BatchWorker.class);

    private final Supplier<Optional<J>> nextJobSupplier;
    private final AbstractBatchJobPersistenceService<J> persistenceService;
    private final BatchStepExecutor<S> executor;
    private final int maxAttempts;

    /**
     * @param nextJobSupplier returns the oldest non-terminal job across every owner, e.g. {@code
     *                        () -> repository.findFirstByStatusInOrderByCreatedAtAsc(List.of(PENDING, PROCESSING))}
     * @param maxAttempts     consecutive failures a step gets before it's dead-lettered (3 unless
     *                        a service has a real reason to differ)
     */
    public BatchWorker(Supplier<Optional<J>> nextJobSupplier,
                                   AbstractBatchJobPersistenceService<J> persistenceService,
                                   BatchStepExecutor<S> executor,
                                   int maxAttempts) {
        this.nextJobSupplier = nextJobSupplier;
        this.persistenceService = persistenceService;
        this.executor = executor;
        this.maxAttempts = maxAttempts;
    }

    /** Call this from your own {@code @Scheduled} method -- see class javadoc for why the
     * annotation itself can't live here. */
    public final void tick() {
        J job = nextJobSupplier.get().orElse(null);
        if (job == null) {
            return;
        }
        try {
            runOneStep(job);
        } catch (Exception ex) {
            log.error("Batch job {} failed unexpectedly, marking FAILED: {}", job.getJobId(), ex.getMessage(), ex);
            persistenceService.finishFailure(job.getJobId(), ex.getMessage());
        }
    }

    private void runOneStep(J job) {
        if (job.getStatus() == JobLifecycleStatus.PENDING) {
            job = persistenceService.markProcessing(job.getJobId());
        }

        List<S> steps = executor.planSteps(job.getTenantId(), job.getOwnerId());
        int totalSteps = steps.size();

        if (job.getCurrentStepIndex() >= totalSteps) {
            persistenceService.finishSuccess(job.getJobId(), j -> j.setCurrentStepLabel("Done"));
            return;
        }

        S step = steps.get(job.getCurrentStepIndex());
        persistenceService.recordProgress(job.getJobId(), executor.label(step));

        UUID tenantId = job.getTenantId();
        UUID jobId = job.getJobId();
        int stepIndex = job.getCurrentStepIndex();
        try {
            executor.execute(tenantId, job.getOwnerId(), step);
            persistenceService.advance(jobId, stepIndex, totalSteps);
        } catch (Exception ex) {
            int attempts = job.getCurrentStepAttempt() + 1;
            log.warn("Batch job {} step {} attempt {} failed: {}", jobId, executor.stepKey(step), attempts, ex.getMessage());
            if (attempts >= maxAttempts) {
                persistenceService.deadLetterAndAdvance(jobId, tenantId, step, executor.stepKey(step),
                        attempts, ex.getMessage(), stepIndex, totalSteps);
            } else {
                persistenceService.recordAttempt(jobId, attempts);
            }
        }
    }
}
