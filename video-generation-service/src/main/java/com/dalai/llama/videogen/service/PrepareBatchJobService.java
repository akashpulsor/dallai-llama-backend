package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.PrepareBatchJobStatus;
import com.dalai.llama.videogen.domain.entity.PrepareBatchJob;
import com.dalai.llama.videogen.kafka.PrepareBatchRequestedEvent;
import com.dalai.llama.videogen.kafka.PrepareBatchRequestedPublisher;
import com.dalai.llama.videogen.repository.PrepareBatchJobRepository;
import com.dalai.llama.videogen.web.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Accepts a prepare-batch: records it, publishes it, and hands the caller something to poll.
 * The work itself happens in {@link com.dalai.llama.videogen.kafka.PrepareBatchRequestedConsumer}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrepareBatchJobService {

    private final PrepareBatchJobRepository prepareBatchJobRepository;
    private final PrepareBatchRequestedPublisher publisher;

    /** @return the accepted job, or the live one already running for this project. A creator
     *  clicking Prepare twice gets told about the first batch rather than paying for a second
     *  run of the same shots. */
    public Accepted submit(TenantContext ctx, UUID projectId, List<UUID> shotIds,
                           ShotContextAssemblyService.PrepareShotOverrides overrides) {
        Optional<PrepareBatchJob> live = prepareBatchJobRepository
                .findFirstByProjectIdAndStatusInOrderByCreatedAtDesc(
                        projectId, List.of(PrepareBatchJobStatus.PENDING, PrepareBatchJobStatus.RUNNING));
        if (live.isPresent()) {
            return new Accepted(live.get(), false);
        }

        PrepareBatchJob job;
        try {
            job = persist(ctx, projectId, shotIds, overrides);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race to a concurrent identical click -- the partial unique index on
            // (project_id) WHERE status IN (PENDING, RUNNING) caught it, which is the point of
            // enforcing this in the database rather than in one pod's memory.
            PrepareBatchJob raced = prepareBatchJobRepository
                    .findFirstByProjectIdAndStatusInOrderByCreatedAtDesc(
                            projectId, List.of(PrepareBatchJobStatus.PENDING, PrepareBatchJobStatus.RUNNING))
                    .orElseThrow(() -> VideoGenException.conflict(
                            "A prepare batch is already running for project " + projectId));
            return new Accepted(raced, false);
        }

        try {
            publisher.publish(new PrepareBatchRequestedEvent(
                    job.getId(), ctx.tenantId().toString(), projectId, ctx.userId()));
        } catch (RuntimeException ex) {
            // Nothing will ever consume this job -- fail it now rather than leave a PENDING row
            // that blocks every future batch for the project via the unique index.
            failPublishFailure(job, ex);
            throw ex;
        }
        return new Accepted(job, true);
    }

    // Not @Transactional: submit() calls this on itself, and Spring's proxy is bypassed on
    // self-invocation, so the annotation would be decoration. It does not need one -- a single
    // repository.save() is already atomic.
    private PrepareBatchJob persist(TenantContext ctx, UUID projectId, List<UUID> shotIds,
                                      ShotContextAssemblyService.PrepareShotOverrides overrides) {
        OffsetDateTime now = OffsetDateTime.now();
        return prepareBatchJobRepository.save(PrepareBatchJob.builder()
                .tenantId(ctx.tenantId())
                .projectId(projectId)
                .createdBy(ctx.userId())
                .status(PrepareBatchJobStatus.PENDING)
                // Null, not an empty string, so the consumer's "null means every shot" contract
                // matches the endpoint's.
                .shotIds(shotIds == null || shotIds.isEmpty()
                        ? null
                        : shotIds.stream().map(UUID::toString).collect(Collectors.joining(",")))
                .modelPin(overrides == null ? null : overrides.modelPin())
                .resolutionOverride(overrides == null ? null : overrides.resolutionOverride())
                .dialogueFlag(flagName(overrides, true))
                .captionsFlag(flagName(overrides, false))
                .preparedCount(0)
                .failedCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void failPublishFailure(PrepareBatchJob job, RuntimeException cause) {
        job.setStatus(PrepareBatchJobStatus.FAILED);
        job.setErrorMessage("Could not submit batch: " + cause.getMessage());
        job.setUpdatedAt(OffsetDateTime.now());
        job.setCompletedAt(OffsetDateTime.now());
        prepareBatchJobRepository.save(job);
    }

    public Optional<PrepareBatchJob> latestForProject(UUID tenantId, UUID projectId) {
        return prepareBatchJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .filter(job -> job.getTenantId().equals(tenantId));
    }

    private String flagName(ShotContextAssemblyService.PrepareShotOverrides overrides, boolean dialogue) {
        if (overrides == null || overrides.featureFlagOverrides() == null) {
            return null;
        }
        var flag = dialogue
                ? overrides.featureFlagOverrides().dialogue()
                : overrides.featureFlagOverrides().captions();
        return flag == null ? null : flag.name();
    }

    /** {@code started} is false when this call joined an existing live batch rather than
     *  queueing a new one. */
    public record Accepted(PrepareBatchJob job, boolean started) {}
}
