package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.repository.ShotPromptRepository;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Reclaims video_gen_job rows stranded in PROCESSING by a pod crash mid-dispatch -- but verifies
 * with llm-gateway before ever declaring one dead, so a tenant is never charged for a generation
 * this service then throws away and never shows them, and is never charged twice for one it
 * re-dispatches unnecessarily.
 *
 * <p>The verification IS a re-call to {@code dispatch()} with the job's already-stable idempotency
 * key ({@code "video-gen-job-" + jobId}, see LlmGatewayVideoGenDispatchService) -- llm-gateway's
 * own idempotency layer decides what actually happens: a still-genuinely-processing job on its
 * side returns immediately with no result (nothing to do here yet); an already-COMPLETED job
 * replays its persisted result (recovered here, not lost); a job stale on llm-gateway's side too
 * self-heals and genuinely redispatches there -- exactly once, on the same row, never a second
 * charge. This service never dispatches to a provider directly, so there is no separate path that
 * could double-charge independently of that.
 *
 * <p>Runs once immediately on startup (this is the "pod came back up, check what's actually true"
 * moment) and then on a fixed interval as an ongoing safety net.
 */
@Slf4j
@Component
public class StaleJobReconciliationTask {

    private final VideoGenJobRepository videoGenJobRepository;
    private final ShotPromptRepository shotPromptRepository;
    private final VideoGenJobPersistenceService jobPersistenceService;
    private final VideoGenDispatchService videoGenDispatchService;
    private final VideoAssetPersistenceService videoAssetPersistenceService;
    private final long staleAfterMinutes;
    private final long giveupAfterMinutes;

    public StaleJobReconciliationTask(
            VideoGenJobRepository videoGenJobRepository,
            ShotPromptRepository shotPromptRepository,
            VideoGenJobPersistenceService jobPersistenceService,
            VideoGenDispatchService videoGenDispatchService,
            VideoAssetPersistenceService videoAssetPersistenceService,
            @Value("${video-gen.job.stale-after-minutes}") long staleAfterMinutes,
            @Value("${video-gen.job.giveup-after-minutes}") long giveupAfterMinutes
    ) {
        this.videoGenJobRepository = videoGenJobRepository;
        this.shotPromptRepository = shotPromptRepository;
        this.jobPersistenceService = jobPersistenceService;
        this.videoGenDispatchService = videoGenDispatchService;
        this.videoAssetPersistenceService = videoAssetPersistenceService;
        this.staleAfterMinutes = staleAfterMinutes;
        this.giveupAfterMinutes = giveupAfterMinutes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("Running startup reconciliation for any PROCESSING video_gen_job rows");
        reclaimStaleJobs();
    }

    @Scheduled(fixedDelayString = "${video-gen.job.reconciliation-interval-ms}")
    public void reclaimStaleJobs() {
        OffsetDateTime staleCutoff = OffsetDateTime.now().minusMinutes(staleAfterMinutes);
        List<VideoGenJob> candidates = videoGenJobRepository
                .findByStatusAndProcessingStartedAtBefore(JobStatus.PROCESSING, staleCutoff);
        for (VideoGenJob job : candidates) {
            try {
                reconcileOne(job);
            } catch (RuntimeException ex) {
                // One job's reconciliation failing (e.g. llm-gateway unreachable this cycle) must
                // not stop the rest of the batch from being checked.
                log.warn("Reconciliation attempt failed jobId={} errorMessage={}", job.getJobId(), ex.getMessage());
            }
        }
    }

    private void reconcileOne(VideoGenJob job) {
        OffsetDateTime giveupCutoff = OffsetDateTime.now().minusMinutes(giveupAfterMinutes);
        boolean pastGiveup = job.getProcessingStartedAt() != null && job.getProcessingStartedAt().isBefore(giveupCutoff);

        ShotPrompt prompt = shotPromptRepository.findByJobIdOrderByCreatedAtDesc(job.getJobId()).stream()
                .findFirst().orElse(null);
        if (prompt == null) {
            // No prompt to replay against -- nothing to verify with, fall back to the age-based
            // ceiling only.
            if (pastGiveup) {
                giveUp(job, "No shot_prompt found for stranded job during reconciliation");
            }
            return;
        }

        String positive = prompt.getCompressionApplied() ? prompt.getPromptCompressed() : prompt.getPromptOriginal();
        DispatchResult result;
        try {
            // Reuse the seed the original dispatch attempt used (persisted before that call in
            // ShotGenerationOrchestrator.approve), so a retry produces the same visual output that
            // the caller was already committed to -- a fresh random seed here would silently
            // change the generated frame from what the user approved.
            result = videoGenDispatchService.dispatch(job, positive, prompt.getNegativePrompt(),
                    new VideoDispatchParams(job.getDurationSeconds(), job.getAspectRatio(), null, null, job.getSeedUsed()));
        } catch (RuntimeException ex) {
            if (pastGiveup) {
                giveUp(job, "llm-gateway unreachable at giveup threshold: " + ex.getMessage());
            } else {
                log.info("Reconciliation check inconclusive jobId={} errorMessage={} -- leaving PROCESSING, retrying next cycle",
                        job.getJobId(), ex.getMessage());
            }
            return;
        }

        if (result != null && result.outputUri() != null && !result.outputUri().isBlank()) {
            // Either a fresh dispatch just completed (this job's llm-gateway row was itself stale
            // and self-healed+redispatched there), or an already-COMPLETED job replayed its
            // persisted result. Either way: a real result exists now and was not previously
            // recorded here -- persist it for real instead of leaving it orphaned.
            log.info("Reconciliation recovered a completed result jobId={} -- persisting", job.getJobId());
            VideoAssetPersistenceService.PersistedAsset asset =
                    videoAssetPersistenceService.persist(job.getJobId(), result.outputUri());
            jobPersistenceService.finishSuccess(
                    job.getJobId(),
                    result.llmGatewayJobId() == null ? null : result.llmGatewayJobId().toString(),
                    result.outputUri(), result.actualCost(), asset.bucket(), asset.objectKey());
            return;
        }

        // llm-gateway reports this job as still genuinely processing (not stale on its side) --
        // do not touch it. Only the age-based ceiling below can end this loop now.
        if (pastGiveup) {
            giveUp(job, "Exceeded giveup threshold (%d min) with llm-gateway still reporting in-progress"
                    .formatted(giveupAfterMinutes));
        }
    }

    private void giveUp(VideoGenJob job, String reason) {
        jobPersistenceService.reclaimIfStillProcessing(job.getJobId(), reason);
        log.warn("Gave up on stranded video_gen_job jobId={} tenantId={} reason={}",
                job.getJobId(), job.getTenantId(), reason);
    }
}
