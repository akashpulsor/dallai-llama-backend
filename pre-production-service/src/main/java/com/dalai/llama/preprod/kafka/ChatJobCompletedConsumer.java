package com.dalai.llama.preprod.kafka;

import com.dalai.llama.preprod.domain.ShotListJobStatus;
import com.dalai.llama.preprod.domain.entity.ShotListJob;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.repository.ShotListJobRepository;
import com.dalai.llama.preprod.service.ShotListGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Listens on {@code llm.job.completed} for outcomes of jobs this service submitted. Filters to
 * this service's own jobs by looking up the arriving event's {@code idempotencyKey} in
 * {@link ShotListJobRepository} -- an unknown key means the event belongs to another service and
 * is silently dropped. On SUCCEEDED, runs the existing shot persistence code path (same as the
 * old sync endpoint used to run inline) and flips the local job row to SUCCEEDED. On FAILED,
 * records the error message and flips to FAILED. The UI's polling then observes either terminal
 * state on its next poll.
 *
 * <p>No thread pool here either -- the Kafka consumer group is the concurrency mechanism, same
 * shape as llm-gateway's worker side. Consumer group is per-service so every replica of
 * pre-production-service, once we scale up, cooperatively drains the same partitions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatJobCompletedConsumer {

    private final ObjectMapper objectMapper;
    private final ShotListJobRepository shotListJobRepository;
    private final ShotListGenerationService shotListGenerationService;

    @KafkaListener(
            topics = "${pre-production.llm-gateway.job-completed-topic}",
            groupId = "${pre-production.llm-gateway.job-completed-consumer-group:pre-production-service-llm-job-completed}"
    )
    public void onMessage(String payload) {
        ChatJobCompletedEvent event;
        try {
            event = objectMapper.readValue(payload, ChatJobCompletedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable ChatJobCompletedEvent payload: {}", ex.getMessage(), ex);
            return;
        }

        Optional<ShotListJob> maybeJob = shotListJobRepository.findByLlmJobIdempotencyKey(event.idempotencyKey());
        if (maybeJob.isEmpty()) {
            // Not one of ours -- another service (or a shot-list job that's already been reaped)
            // owns this idempotency key. Silent drop, not an error.
            return;
        }
        ShotListJob job = maybeJob.get();

        if (event.status() == ChatJobCompletedEvent.Status.FAILED) {
            markFailed(job, event.errorMessage() == null ? "llm-gateway reported FAILED with no message" : event.errorMessage());
            return;
        }
        try {
            handleSuccess(job, event);
        } catch (Exception ex) {
            // Post-LLM persistence failed (parse error, DB write failure, etc.) -- the LLM call
            // itself already succeeded and was billed, so this must not be treated as retryable
            // by re-throwing; instead terminate the job as FAILED with the local error so the UI
            // stops polling and shows a clean error rather than spinning forever.
            log.error("Post-response persistence failed for shot-list jobId={}", job.getId(), ex);
            markFailed(job, "Post-response persistence failed: " + ex.getMessage());
        }
    }

    @Transactional
    protected void handleSuccess(ShotListJob job, ChatJobCompletedEvent event) {
        List<ShotView> shots = shotListGenerationService.persistFromLlmResponse(
                job.getTenantId(), job.getProjectId(), event.response());
        markSucceeded(job);
        log.info("Shot-list generation succeeded jobId={} projectId={} shotCount={}",
                job.getId(), job.getProjectId(), shots.size());

        // Fire-and-forget the same follow-up planning the sync path used to run inline -- these
        // are best-effort per shot (see their own javadoc in ShotListGenerationService) and any
        // one hiccup mustn't roll back a shot list that already generated correctly.
        try {
            shotListGenerationService.planMotionGraphicShots(job.getTenantId(), shots);
        } catch (Exception ex) {
            log.warn("planMotionGraphicShots failed for jobId={}: {}", job.getId(), ex.getMessage());
        }
        try {
            shotListGenerationService.planLightingAndCameraForShots(job.getTenantId(), shots);
        } catch (Exception ex) {
            log.warn("planLightingAndCameraForShots failed for jobId={}: {}", job.getId(), ex.getMessage());
        }
    }

    @Transactional
    protected void markSucceeded(ShotListJob job) {
        OffsetDateTime now = OffsetDateTime.now();
        job.setStatus(ShotListJobStatus.SUCCEEDED);
        job.setErrorMessage(null);
        job.setUpdatedAt(now);
        job.setCompletedAt(now);
        shotListJobRepository.save(job);
    }

    @Transactional
    protected void markFailed(ShotListJob job, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        job.setStatus(ShotListJobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(now);
        job.setCompletedAt(now);
        shotListJobRepository.save(job);
        log.warn("Shot-list generation failed jobId={} projectId={} reason={}",
                job.getId(), job.getProjectId(), errorMessage);
    }
}
