package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotListJobStatus;
import com.dalai.llama.preprod.domain.entity.ShotListJob;
import com.dalai.llama.preprod.dto.ShotListJobView;
import com.dalai.llama.preprod.kafka.ChatJobRequestedEvent;
import com.dalai.llama.preprod.kafka.ChatJobRequestedPublisher;
import com.dalai.llama.preprod.repository.ShotListJobRepository;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Owns the async shot-list-generation job lifecycle from the caller side. Steps: (1) build the
 * exact LLM request the sync path would have sent, (2) insert a local {@link ShotListJob} row in
 * PENDING with the shared idempotency key so the completed-event consumer can find it, (3)
 * publish {@link ChatJobRequestedEvent} to {@code llm.job.requested} for llm-gateway's worker to
 * pick up. The controller returns immediately with the created job's id; the terminal
 * SUCCEEDED/FAILED transition happens later inside
 * {@link com.dalai.llama.preprod.kafka.ChatJobCompletedConsumer}.
 *
 * <p>Idempotency: the local job row's {@code llm_job_idempotency_key} column has a UNIQUE index
 * (see V49), so a duplicate POST for the same project won't create two jobs; the existing job
 * is returned and re-queued (llm-gateway itself is idempotent on the same key too, so no second
 * Gemini call happens).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotListGenerationJobService {

    private final ShotListJobRepository shotListJobRepository;
    private final ShotListGenerationService shotListGenerationService;
    private final ChatJobRequestedPublisher chatJobRequestedPublisher;

    /**
     * Submits a new (or replays an existing not-yet-terminal) shot-list generation job. Returns
     * the view the controller responds with. If a prior job for this project already succeeded,
     * a fresh one is created -- generation is naturally re-entrant (see {@link
     * ShotListGenerationService#persistFromLlmResponse} which wipes and rewrites the shot rows).
     */
    @Transactional
    public ShotListJobView submit(UUID tenantId, UUID projectId) {
        // Fail fast if the prerequisites (script, screenplay, scenes) aren't there -- surface a
        // clean 400 to the client synchronously rather than persisting a job that would only fail
        // on the worker side moments later. buildChatRequest throws PreProductionException
        // (badRequest/notFound) for each of those preconditions.
        LlmGatewayChatRequest request = shotListGenerationService.buildChatRequest(tenantId, projectId);

        String idempotencyKey = ShotListGenerationService.shotListIdempotencyKey(projectId);
        ShotListJob job = shotListJobRepository.findByLlmJobIdempotencyKey(idempotencyKey)
                .map(existing -> resubmitIfTerminal(existing, tenantId))
                .orElseGet(() -> createPending(tenantId, projectId, idempotencyKey));

        chatJobRequestedPublisher.publish(new ChatJobRequestedEvent(
                tenantId.toString(), idempotencyKey, request));
        log.info("Submitted shot-list generation jobId={} projectId={} idempotencyKey={}",
                job.getId(), projectId, idempotencyKey);
        return ShotListJobView.from(job);
    }

    @Transactional(readOnly = true)
    public ShotListJobView get(UUID tenantId, UUID projectId, UUID jobId) {
        ShotListJob job = shotListJobRepository.findById(jobId)
                .filter(j -> j.getTenantId().equals(tenantId) && j.getProjectId().equals(projectId))
                .orElseThrow(() -> PreProductionException.notFound(
                        "No shot-list job " + jobId + " for project " + projectId));
        return ShotListJobView.from(job);
    }

    private ShotListJob createPending(UUID tenantId, UUID projectId, String idempotencyKey) {
        OffsetDateTime now = OffsetDateTime.now();
        return shotListJobRepository.save(ShotListJob.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .status(ShotListJobStatus.PENDING)
                .llmJobIdempotencyKey(idempotencyKey)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /** For a job row whose previous run finished terminally, reset it to PENDING and reuse the
     * row rather than inserting a duplicate -- the UNIQUE index on {@code llm_job_idempotency_key}
     * would reject the second insert anyway. For a job still PENDING, hand back the existing row
     * unchanged so a page-reload during an in-flight run doesn't kick off a second Kafka message
     * for the same work. */
    private ShotListJob resubmitIfTerminal(ShotListJob existing, UUID tenantId) {
        if (!existing.getTenantId().equals(tenantId)) {
            throw PreProductionException.badRequest(
                    "Shot-list job idempotency key already exists for a different tenant");
        }
        if (existing.getStatus() == ShotListJobStatus.PENDING) {
            log.info("Shot-list job jobId={} still PENDING -- reusing existing submission", existing.getId());
            return existing;
        }
        existing.setStatus(ShotListJobStatus.PENDING);
        existing.setErrorMessage(null);
        existing.setCompletedAt(null);
        existing.setUpdatedAt(OffsetDateTime.now());
        return shotListJobRepository.save(existing);
    }
}
