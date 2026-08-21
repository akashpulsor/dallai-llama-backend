package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.PostProductionStatus;
import com.dalai.llama.postprod.domain.entity.PostProductionJob;
import com.dalai.llama.postprod.repository.PostProductionJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Reclaims post_production_job rows stranded in PROCESSING by a pod crash mid-dispatch.
 *
 * <p>Simpler than video-generation-service's own reconciliation task on purpose: that one can
 * safely re-verify with llm-gateway using a single stable idempotency key, because a video
 * dispatch is one provider call. Dialogue-sync is two chained provider calls (voice-clone, then
 * lip-sync) behind DialogueSyncCoordinator's own orchestration -- safely re-verifying "did this
 * specific step already happen" from outside that orchestration is a real, separate problem, not
 * yet solved here. This declares a stranded job TIMED_OUT unconditionally past the staleness
 * threshold; the video-generation-service-style "ask the gateway first" reconciliation is a named
 * fast-follow once dialogue-sync's own step-level idempotency is designed, not silently
 * papered over as already solved.
 */
@Slf4j
@Component
public class StaleJobReconciliationTask {

    private final PostProductionJobRepository repository;
    private final PostProductionJobPersistenceService jobPersistenceService;
    private final long staleAfterMinutes;

    public StaleJobReconciliationTask(
            PostProductionJobRepository repository,
            PostProductionJobPersistenceService jobPersistenceService,
            @Value("${post-production.job.stale-after-minutes}") long staleAfterMinutes
    ) {
        this.repository = repository;
        this.jobPersistenceService = jobPersistenceService;
        this.staleAfterMinutes = staleAfterMinutes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("Running startup reconciliation for any PROCESSING post_production_job rows");
        reclaimStaleJobs();
    }

    @Scheduled(fixedDelayString = "${post-production.job.reconciliation-interval-ms}")
    public void reclaimStaleJobs() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(staleAfterMinutes);
        List<PostProductionJob> candidates = repository.findByStatusAndProcessingStartedAtBefore(PostProductionStatus.PROCESSING, cutoff);
        for (PostProductionJob job : candidates) {
            jobPersistenceService.reclaimIfStillProcessing(job.getJobId(),
                    "Stranded PROCESSING row exceeded staleness threshold (%d min), presumed crashed"
                            .formatted(staleAfterMinutes));
            log.warn("Reclaimed stale post_production_job jobId={} tenantId={}", job.getJobId(), job.getTenantId());
        }
    }
}
