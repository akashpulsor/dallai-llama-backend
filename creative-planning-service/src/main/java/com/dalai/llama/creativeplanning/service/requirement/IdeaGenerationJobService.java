package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.IdeaGenerationJobStatus;
import com.dalai.llama.creativeplanning.domain.entity.IdeaGenerationJob;
import com.dalai.llama.creativeplanning.dto.IdeaGenerationJobView;
import com.dalai.llama.creativeplanning.kafka.ChatJobRequestedEvent;
import com.dalai.llama.creativeplanning.kafka.ChatJobRequestedPublisher;
import com.dalai.llama.creativeplanning.repository.IdeaGenerationJobRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side owner of the async idea-generation job lifecycle -- mirror of pre-production
 * -service's {@code ShotListGenerationJobService}. The three-step submit is: (1) validate and
 * build the exact LLM request the sync path would have via
 * {@link ProjectRequirementIdeaService#buildChatRequest}, (2) insert a local
 * {@link IdeaGenerationJob} row in PENDING with the shared idempotency key, (3) publish
 * {@link ChatJobRequestedEvent} to {@code llm.job.requested}. The controller returns 202 + jobId
 * immediately; the terminal transition happens later inside
 * {@link com.dalai.llama.creativeplanning.kafka.ChatJobCompletedConsumer}.
 *
 * <p>Idempotency: the local job row's {@code llm_job_idempotency_key} column has a UNIQUE index
 * (see V18). A duplicate publish for the same jobId won't create two rows; the existing row is
 * returned and re-queued. llm-gateway itself is idempotent on the same key too, so no second
 * Gemini call happens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdeaGenerationJobService {

    private final IdeaGenerationJobRepository ideaGenerationJobRepository;
    private final ProjectRequirementIdeaService projectRequirementIdeaService;
    private final ChatJobRequestedPublisher chatJobRequestedPublisher;

    /** Submits a new async idea-generation job. Runs the funded-requirement / option-count
     * validation on the request thread (surfaces a clean 400 to the client) before persisting
     * anything or publishing to Kafka, so a bad request doesn't leave a stranded PENDING row. */
    @Transactional
    public IdeaGenerationJobView submit(UUID tenantId, UUID requirementId, Integer count) {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = ProjectRequirementIdeaService.ideaGenerationIdempotencyKey(requirementId, jobId);
        // buildChatRequest throws CreativePlanningException.badRequest for
        // not-funded / not-found; that surfaces synchronously as a 400 before any row is written.
        LlmGatewayChatRequest request = projectRequirementIdeaService.buildChatRequest(tenantId, requirementId, count);
        IdeaGenerationJob job = createPending(jobId, tenantId, requirementId, count, idempotencyKey);

        chatJobRequestedPublisher.publish(new ChatJobRequestedEvent(
                tenantId.toString(), idempotencyKey, request));
        log.info("Submitted idea-generation jobId={} requirementId={} idempotencyKey={}",
                job.getId(), requirementId, idempotencyKey);
        return IdeaGenerationJobView.from(job);
    }

    @Transactional(readOnly = true)
    public IdeaGenerationJobView get(UUID tenantId, UUID requirementId, UUID jobId) {
        IdeaGenerationJob job = ideaGenerationJobRepository.findById(jobId)
                .filter(j -> j.getTenantId().equals(tenantId) && j.getProjectRequirementId().equals(requirementId))
                .orElseThrow(() -> CreativePlanningException.notFound(
                        "No idea-generation job " + jobId + " for requirement " + requirementId));
        return IdeaGenerationJobView.from(job);
    }

    /** Latest job for this requirement, or empty if none was ever submitted. Backs the "rehydrate
     * a FAILED/PENDING run on page reload" flow the UI does on mount -- same rationale as the
     * shot-list latest endpoint (without it, the panel shows the empty-options state a fresh
     * requirement shows, hiding both the reason for the empty state and the fact that a
     * generation was already attempted). */
    @Transactional(readOnly = true)
    public Optional<IdeaGenerationJobView> latest(UUID tenantId, UUID requirementId) {
        return ideaGenerationJobRepository
                .findTopByProjectRequirementIdAndTenantIdOrderByCreatedAtDesc(requirementId, tenantId)
                .map(IdeaGenerationJobView::from);
    }

    private IdeaGenerationJob createPending(UUID jobId, UUID tenantId, UUID requirementId,
                                            Integer count, String idempotencyKey) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            return ideaGenerationJobRepository.save(IdeaGenerationJob.builder()
                    .id(jobId)
                    .tenantId(tenantId)
                    .projectRequirementId(requirementId)
                    .requestedOptionCount(count)
                    .status(IdeaGenerationJobStatus.PENDING)
                    .llmJobIdempotencyKey(idempotencyKey)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // Same idempotency-key race guard as ShotListGenerationJobService: another concurrent
            // request already inserted the same jobId+key between our findBy and save. Reuse
            // the winner's row instead of failing the second caller with a 500.
            log.info("Idempotency-key race on idea-generation submit -- reusing winner's row idempotencyKey={}",
                    idempotencyKey);
            return ideaGenerationJobRepository.findByLlmJobIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> CreativePlanningException.upstream(
                            "Idempotency race resolved but row disappeared for key " + idempotencyKey));
        }
    }
}
