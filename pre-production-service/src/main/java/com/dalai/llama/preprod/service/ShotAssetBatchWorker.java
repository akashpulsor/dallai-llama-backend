package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.BatchWorker;
import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.preprod.domain.entity.ShotAssetBatchJob;
import com.dalai.llama.preprod.repository.ShotAssetBatchJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Thin wiring only -- the queue/retry/dead-letter algorithm is job-lifecycle-common's {@link
 * BatchWorker}; this just supplies pre-production's own repository lookup, persistence service,
 * and executor, and provides the {@code @Scheduled} method Spring needs (scheduling can't be
 * triggered from the shared library itself -- see BatchWorker's own javadoc). */
@Component
public class ShotAssetBatchWorker {

    /** A step gets 3 tries -- one clean shot at transient flakiness, not indefinite retry against
     * a genuinely broken prompt/shot that will never succeed on its own. */
    private static final int MAX_ATTEMPTS = 3;

    private final BatchWorker<ShotAssetBatchJob, ShotAssetStep> worker;

    public ShotAssetBatchWorker(
            ShotAssetBatchJobRepository jobRepository,
            ShotAssetBatchJobPersistenceService jobPersistenceService,
            ShotAssetBatchExecutor executor
    ) {
        this.worker = new BatchWorker<>(
                () -> jobRepository.findFirstByStatusInOrderByCreatedAtAsc(List.of(JobLifecycleStatus.PENDING, JobLifecycleStatus.PROCESSING)),
                jobPersistenceService,
                executor,
                MAX_ATTEMPTS
        );
    }

    @Scheduled(fixedDelayString = "${pre-production.shot-asset-batch.tick-interval-ms}")
    public void tick() {
        worker.tick();
    }
}
