package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.ApprovalStatus;
import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Owns every video_gen_job state transition, each in its own short transaction. Deliberately a
 * separate bean from {@link ShotGenerationOrchestrator}: {@code approve()} used to be
 * {@code @Transactional} on the orchestrator itself, which held one Hikari connection checked out
 * for the entire blocking dispatch+MinIO-persist chain (potentially minutes) -- during that whole
 * window the PROCESSING write was sitting uncommitted, invisible to any concurrent GET, and a
 * handful of simultaneous approvals could exhaust the pool (default max 10) and stall every other
 * request this service handles, including plain status reads. Splitting persistence into its own
 * bean with short-lived transactions -- called from a non-transactional orchestrator method around
 * the blocking work -- mirrors llm-gateway's {@code JobPersistenceService}, which exists for the
 * exact same reason (see its own class comment for the {@code @Transactional} self-invocation
 * pitfall this split also avoids).
 */
@Service
public class VideoGenJobPersistenceService {

    private final VideoGenJobRepository videoGenJobRepository;

    public VideoGenJobPersistenceService(VideoGenJobRepository videoGenJobRepository) {
        this.videoGenJobRepository = videoGenJobRepository;
    }

    /** Commits immediately -- a concurrent GET (e.g. a refreshed page re-polling) sees PROCESSING
     * for real from this point, not just after the whole dispatch chain finishes.
     * processingStartedAt is what {@code StaleJobReconciliationTask} measures staleness from. */
    @Transactional
    public VideoGenJob markProcessing(UUID jobId) {
        VideoGenJob job = requireJob(jobId);
        job.setApprovalStatus(ApprovalStatus.APPROVED);
        job.setStatus(JobStatus.PROCESSING);
        job.setProcessingStartedAt(OffsetDateTime.now());
        return videoGenJobRepository.save(job);
    }

    @Transactional
    public VideoGenJob finishSuccess(UUID jobId, String llmGatewayJobId, String outputUri,
                                      BigDecimal actualCost, String outputBucket, String outputObjectKey) {
        VideoGenJob job = requireJob(jobId);
        job.setLlmGatewayJobId(llmGatewayJobId);
        job.setOutputUri(outputUri);
        job.setActualCost(actualCost);
        job.setOutputBucket(outputBucket);
        job.setOutputObjectKey(outputObjectKey);
        job.setStatus(JobStatus.COMPLETED);
        job.setCompletedAt(OffsetDateTime.now());
        return videoGenJobRepository.save(job);
    }

    @Transactional
    public VideoGenJob finishFailure(UUID jobId, String errorMessage) {
        VideoGenJob job = requireJob(jobId);
        job.setStatus(JobStatus.FAILED);
        job.setLastError(errorMessage);
        job.setCompletedAt(OffsetDateTime.now());
        return videoGenJobRepository.save(job);
    }

    /** Idempotent: no-ops (returns empty) if the row already reached a terminal state, so a racing
     * finishSuccess/finishFailure from the still-actually-running dispatch and the reconciliation
     * task can't both write -- whichever arrives first wins. */
    @Transactional
    public java.util.Optional<VideoGenJob> reclaimIfStillProcessing(UUID jobId, String reason) {
        VideoGenJob job = requireJob(jobId);
        if (job.getStatus() != JobStatus.PROCESSING) {
            return java.util.Optional.empty();
        }
        job.setStatus(JobStatus.TIMED_OUT);
        job.setLastError(reason);
        job.setCompletedAt(OffsetDateTime.now());
        return java.util.Optional.of(videoGenJobRepository.save(job));
    }

    private VideoGenJob requireJob(UUID jobId) {
        return videoGenJobRepository.findById(jobId)
                .orElseThrow(() -> VideoGenException.notFound("Unknown job_id: " + jobId));
    }
}
