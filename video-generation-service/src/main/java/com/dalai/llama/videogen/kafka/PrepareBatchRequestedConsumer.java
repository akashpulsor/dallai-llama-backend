package com.dalai.llama.videogen.kafka;

import com.dalai.llama.videogen.domain.PrepareBatchJobStatus;
import com.dalai.llama.videogen.domain.entity.PrepareBatchJob;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.domain.FlagState;
import com.dalai.llama.videogen.repository.PrepareBatchJobRepository;
import com.dalai.llama.videogen.service.PrepareOrchestrationService;
import com.dalai.llama.videogen.service.ShotContextAssemblyService;
import com.dalai.llama.videogen.web.TenantContext;
import com.dalai.llama.videogen.web.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs an accepted prepare-batch off the request thread.
 *
 * <p>No thread pool: the Kafka consumer group is the concurrency mechanism, the same shape
 * pre-production-service uses for its shot-list jobs. That matters for more than tidiness -- a
 * pool loses every queued batch when the pod restarts, cannot be retuned without a redeploy, and
 * gives two replicas no way to agree on who is running what. Here the work is durable in
 * prepare_batch_job, concurrency is one consumer per partition, and a restart re-delivers rather
 * than drops.
 *
 * <p>Parameters are read from the job row, not from the event. A replayed or late-delivered event
 * therefore re-reads the request as recorded rather than acting on a stale copy of it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrepareBatchRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final PrepareBatchJobRepository prepareBatchJobRepository;
    private final PrepareOrchestrationService prepareOrchestrationService;

    @KafkaListener(
            topics = "${video-gen.prepare.requested-topic}",
            groupId = "${video-gen.prepare.requested-consumer-group:video-generation-service-prepare-batch}"
    )
    public void onMessage(String payload) {
        PrepareBatchRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, PrepareBatchRequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable PrepareBatchRequestedEvent payload: {}", ex.getMessage(), ex);
            return;
        }

        Optional<PrepareBatchJob> maybeJob = prepareBatchJobRepository.findById(event.jobId());
        if (maybeJob.isEmpty()) {
            log.warn("Dropping prepare-batch event for unknown jobId={}", event.jobId());
            return;
        }
        PrepareBatchJob job = maybeJob.get();
        if (job.getStatus() != null && !job.getStatus().isLive()) {
            // Already finished -- a redelivery after the consumer committed the work but before
            // the offset. Re-running would redo every shot and bill for it twice.
            log.info("Ignoring redelivered prepare-batch jobId={} already {}", job.getId(), job.getStatus());
            return;
        }

        markRunning(job);
        // TenantContextHolder is a ThreadLocal the servlet filter fills, so a consumer thread has
        // none and every llm-gateway call downstream would fail on the missing tenant. Rebuild it
        // from the event and clear it after, since consumer threads are reused.
        TenantContext ctx = new TenantContext(UUID.fromString(event.tenantId()), event.userId());
        TenantContextHolder.set(ctx);
        try {
            PrepareOrchestrationService.BatchResult result = prepareOrchestrationService.prepareShotsBatch(
                    ctx, job.getProjectId(), parseShotIds(job.getShotIds()), overridesFrom(job));
            markSucceeded(job, result.prepared().size(), result.failed().size());
        } catch (RuntimeException ex) {
            // prepareShotsBatch has already flipped the project's scene-preparation status to
            // FAILED and logged the cause; this records it against the job the UI is polling.
            log.warn("prepare-batch jobId={} projectId={} failed: {}", job.getId(), job.getProjectId(), ex.getMessage());
            markFailed(job, ex.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void markRunning(PrepareBatchJob job) {
        job.setStatus(PrepareBatchJobStatus.RUNNING);
        job.setUpdatedAt(OffsetDateTime.now());
        prepareBatchJobRepository.save(job);
    }

    private void markSucceeded(PrepareBatchJob job, int prepared, int failed) {
        job.setStatus(PrepareBatchJobStatus.SUCCEEDED);
        job.setPreparedCount(prepared);
        job.setFailedCount(failed);
        job.setUpdatedAt(OffsetDateTime.now());
        job.setCompletedAt(OffsetDateTime.now());
        prepareBatchJobRepository.save(job);
    }

    private void markFailed(PrepareBatchJob job, String errorMessage) {
        job.setStatus(PrepareBatchJobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(OffsetDateTime.now());
        job.setCompletedAt(OffsetDateTime.now());
        prepareBatchJobRepository.save(job);
    }

    /** Null/blank means "every shot in the project" -- the same contract the endpoint takes. */
    private List<UUID> parseShotIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();
    }

    private ShotContextAssemblyService.PrepareShotOverrides overridesFrom(PrepareBatchJob job) {
        FeatureFlags flags = job.getDialogueFlag() == null && job.getCaptionsFlag() == null
                ? null
                : new FeatureFlags(parseFlag(job.getDialogueFlag()), parseFlag(job.getCaptionsFlag()));
        return new ShotContextAssemblyService.PrepareShotOverrides(
                flags, job.getModelPin(), null, null, job.getResolutionOverride());
    }

    private FlagState parseFlag(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FlagState.valueOf(value);
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown flag state on prepare batch job: {}", value);
            return null;
        }
    }
}
