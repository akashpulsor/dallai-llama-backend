package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.PostProductionStatus;
import com.dalai.llama.postprod.domain.entity.PostProductionJob;
import com.dalai.llama.postprod.repository.PostProductionJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every post_production_job state transition, each in its own short transaction -- built in
 * from day one this time, not retrofitted after finding the bug live (see
 * video-generation-service's VideoGenJobPersistenceService class comment for the incident this
 * pattern was learned from: a method-level @Transactional wrapping a multi-minute blocking
 * dispatch chain holds a pooled DB connection the whole time, leaves state writes uncommitted and
 * invisible to concurrent readers until the very end, and risks exhausting the connection pool
 * under a few concurrent jobs).
 */
@Service
public class PostProductionJobPersistenceService {

    private final PostProductionJobRepository repository;

    public PostProductionJobPersistenceService(PostProductionJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PostProductionJob markProcessing(UUID jobId) {
        PostProductionJob job = requireJob(jobId);
        job.setStatus(PostProductionStatus.PROCESSING);
        job.setProcessingStartedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    @Transactional
    public PostProductionJob finishSuccess(UUID jobId, UUID dialogueSyncJobId, String outputBucket, String outputObjectKey) {
        PostProductionJob job = requireJob(jobId);
        job.setDialogueSyncJobId(dialogueSyncJobId);
        job.setOutputBucket(outputBucket);
        job.setOutputObjectKey(outputObjectKey);
        job.setStatus(PostProductionStatus.COMPLETED);
        job.setCompletedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    @Transactional
    public PostProductionJob finishFailure(UUID jobId, String errorMessage) {
        PostProductionJob job = requireJob(jobId);
        job.setStatus(PostProductionStatus.FAILED);
        job.setLastError(errorMessage);
        job.setCompletedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    /** Idempotent: no-ops if the row already reached a terminal state, so a racing genuinely-
     * still-running finish and the staleness reconciliation task can't both write. */
    @Transactional
    public Optional<PostProductionJob> reclaimIfStillProcessing(UUID jobId, String reason) {
        PostProductionJob job = requireJob(jobId);
        if (job.getStatus() != PostProductionStatus.PROCESSING) {
            return Optional.empty();
        }
        job.setStatus(PostProductionStatus.TIMED_OUT);
        job.setLastError(reason);
        job.setCompletedAt(OffsetDateTime.now());
        return Optional.of(repository.save(job));
    }

    private PostProductionJob requireJob(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> PostProductionException.notFound("Unknown job_id: " + jobId));
    }
}
