package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.BatchDeadLetterSink;
import com.dalai.llama.preprod.domain.entity.ShotAssetBatchDeadLetter;
import com.dalai.llama.preprod.dto.ShotAssetBatchDeadLetterView;
import com.dalai.llama.preprod.repository.ShotAssetBatchDeadLetterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** The pre-production-specific half of the dead-letter contract: {@link #deadLetter} is what
 * job-lifecycle-common's {@code BatchWorker} calls (via {@code ShotAssetBatchJobPersistenceService})
 * on a step's 3rd consecutive failure; {@link #list}/{@link #retry} are the manual side a creator
 * actually interacts with. A retry is a single {@link ShotAssetBatchExecutor#execute} call, the
 * same one-step unit of work the worker itself runs, just triggered directly instead of from the
 * queue -- never anything automatic. */
@Service
public class ShotAssetBatchDeadLetterService implements BatchDeadLetterSink<ShotAssetStep> {

    private final ShotAssetBatchDeadLetterRepository repository;
    private final ShotAssetBatchExecutor executor;

    public ShotAssetBatchDeadLetterService(ShotAssetBatchDeadLetterRepository repository, ShotAssetBatchExecutor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    @Override
    @Transactional
    public void deadLetter(UUID jobId, UUID tenantId, UUID projectId, ShotAssetStep step, String stepKey, int attempts, String lastError) {
        repository.save(ShotAssetBatchDeadLetter.builder()
                .id(UUID.randomUUID())
                .batchJobId(jobId)
                .tenantId(tenantId)
                .projectId(projectId)
                .shotId(step.shotId())
                .step(step.kind().name())
                .attempts(attempts)
                .lastError(lastError)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<ShotAssetBatchDeadLetterView> list(UUID tenantId, UUID projectId) {
        return repository.findByProjectIdAndResolvedFalseOrderByCreatedAtAsc(projectId).stream()
                .filter(d -> d.getTenantId().equals(tenantId))
                .map(d -> new ShotAssetBatchDeadLetterView(d.getId(), d.getShotId(), d.getStep(), d.getAttempts(), d.getLastError(), d.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /** Runs the one step this dead letter recorded, directly -- errors surface to the caller
     * (same as any other manual "Regenerate" action) rather than being swallowed and re-queued;
     * the creator asked for this one explicitly, so they should see if it fails again. */
    @Transactional
    public void retry(UUID tenantId, UUID projectId, UUID deadLetterId) {
        ShotAssetBatchDeadLetter deadLetter = repository.findById(deadLetterId)
                .filter(d -> d.getTenantId().equals(tenantId) && d.getProjectId().equals(projectId))
                .orElseThrow(() -> PreProductionException.notFound("No dead-lettered step " + deadLetterId));
        if (Boolean.TRUE.equals(deadLetter.getResolved())) {
            return;
        }
        ShotAssetStep.Kind kind = ShotAssetStep.Kind.valueOf(deadLetter.getStep());
        try {
            // shotNumber/totalShots are display-only (see ShotAssetStep's javadoc) and unused by
            // execute() -- 0 is a harmless placeholder for a one-off manual retry outside the batch.
            executor.execute(tenantId, projectId, new ShotAssetStep(deadLetter.getShotId(), 0, 0, kind));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            // BatchStepExecutor#execute declares a checked Exception for generality across
            // job-lifecycle-common's other possible implementers; none of this executor's own
            // underlying calls (LightingPlanService/CameraPlanService/ShotImageService) actually
            // throw one, but the interface still requires handling it here.
            throw PreProductionException.upstream("Retry failed: " + ex.getMessage());
        }
        deadLetter.setResolved(true);
        deadLetter.setResolvedAt(OffsetDateTime.now());
        repository.save(deadLetter);
    }
}
