package com.dalai.llama.joblifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Shared "give up on stranded PROCESSING rows" sweep. Holds the logic only -- concrete
 * subclasses in each service are the actual {@code @Component} beans and own the
 * {@code @Scheduled}/{@code @EventListener} wiring, since the interval and startup behavior are
 * per-service configuration, not something this shared module should dictate.
 * <p>
 * {@link #beforeReclaim(TrackedJob)} is the extension point for a smarter check before giving
 * up on a job (e.g. verifying against the upstream gateway first) -- default is the simple
 * unconditional give-up every service uses today.
 */
public abstract class AbstractStaleJobReconciliationTask<T extends TrackedJob> {

    private static final Logger log = LoggerFactory.getLogger(AbstractStaleJobReconciliationTask.class);

    protected final StaleJobRepository<T> repository;
    protected final AbstractJobLifecycleService<T> lifecycleService;
    protected final long staleAfterMinutes;

    protected AbstractStaleJobReconciliationTask(StaleJobRepository<T> repository,
                                                  AbstractJobLifecycleService<T> lifecycleService,
                                                  long staleAfterMinutes) {
        this.repository = repository;
        this.lifecycleService = lifecycleService;
        this.staleAfterMinutes = staleAfterMinutes;
    }

    public void reconcileOnStartup() {
        reclaimStaleJobs();
    }

    public void reclaimStaleJobs() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(staleAfterMinutes);
        List<T> candidates = repository.findByStatusAndProcessingStartedAtBefore(JobLifecycleStatus.PROCESSING, cutoff);
        if (candidates.isEmpty()) {
            return;
        }
        String reason = "Stranded PROCESSING row exceeded staleness threshold (" + staleAfterMinutes + " min), presumed crashed";
        for (T job : candidates) {
            if (!beforeReclaim(job)) {
                continue;
            }
            lifecycleService.reclaimIfStillProcessing(job.getJobId(), reason);
            log.warn("Reclaimed stale job {} after exceeding {} min in PROCESSING", job.getJobId(), staleAfterMinutes);
        }
    }

    /**
     * @return true to reclaim this job as {@code TIMED_OUT}; false to leave it alone this pass
     *         (e.g. because a gateway-side check found it's actually still alive).
     */
    protected boolean beforeReclaim(T job) {
        return true;
    }
}
