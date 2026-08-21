package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.PostProductionStatus;
import com.dalai.llama.postprod.domain.entity.DubbingJob;
import com.dalai.llama.postprod.repository.DubbingJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/** Same reasoning and same simpler-than-video-gen's-own-reconciliation tradeoff as
 * StaleJobReconciliationTask -- dubbing's own chain is even longer (transcribe, translate,
 * clone, synthesize, lip-sync), so step-level gateway-verified reconciliation is a larger, later
 * piece of work; this is the same unconditional-past-threshold safety net in the meantime. */
@Slf4j
@Component
public class StaleDubbingJobReconciliationTask {

    private final DubbingJobRepository repository;
    private final DubbingJobPersistenceService jobPersistenceService;
    private final long staleAfterMinutes;

    public StaleDubbingJobReconciliationTask(
            DubbingJobRepository repository,
            DubbingJobPersistenceService jobPersistenceService,
            @Value("${post-production.job.stale-after-minutes}") long staleAfterMinutes
    ) {
        this.repository = repository;
        this.jobPersistenceService = jobPersistenceService;
        this.staleAfterMinutes = staleAfterMinutes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reclaimStaleJobs();
    }

    @Scheduled(fixedDelayString = "${post-production.job.reconciliation-interval-ms}")
    public void reclaimStaleJobs() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(staleAfterMinutes);
        List<DubbingJob> candidates = repository.findByStatusAndProcessingStartedAtBefore(PostProductionStatus.PROCESSING, cutoff);
        for (DubbingJob job : candidates) {
            jobPersistenceService.reclaimIfStillProcessing(job.getJobId(),
                    "Stranded PROCESSING row exceeded staleness threshold (%d min), presumed crashed"
                            .formatted(staleAfterMinutes));
            log.warn("Reclaimed stale dubbing_job jobId={} tenantId={}", job.getJobId(), job.getTenantId());
        }
    }
}
