package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.AbstractStaleJobReconciliationTask;
import com.dalai.llama.preprod.domain.entity.GenerationJob;
import com.dalai.llama.preprod.repository.GenerationJobRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Concrete wiring only -- lifecycle logic lives in the shared abstract base
 * (job-lifecycle-common's §12 DRY fix). Reconciles on startup (crash recovery) and on a fixed
 * interval thereafter. */
@Component
public class StaleGenerationJobReconciliationTask extends AbstractStaleJobReconciliationTask<GenerationJob> {

    public StaleGenerationJobReconciliationTask(
            GenerationJobRepository repository,
            GenerationJobPersistenceService lifecycleService,
            @Value("${pre-production.job.stale-after-minutes}") long staleAfterMinutes
    ) {
        super(repository, lifecycleService, staleAfterMinutes);
    }

    @PostConstruct
    public void onStartup() {
        reconcileOnStartup();
    }

    @Scheduled(fixedDelayString = "${pre-production.job.reconciliation-interval-ms}")
    public void onSchedule() {
        reclaimStaleJobs();
    }
}
