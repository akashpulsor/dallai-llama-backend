package com.dalai.llama.creativeplanning.kafka;

import com.dalai.llama.creativeplanning.domain.IdeaGenerationJobStatus;
import com.dalai.llama.creativeplanning.domain.entity.IdeaGenerationJob;
import com.dalai.llama.creativeplanning.dto.IdeaOptionView;
import com.dalai.llama.creativeplanning.repository.IdeaGenerationJobRepository;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementIdeaService;
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
 * Listens on {@code llm.job.completed} for outcomes of idea-generation jobs this service
 * submitted. Filters to its own by looking up the arriving event's {@code idempotencyKey} in
 * {@link IdeaGenerationJobRepository} -- an unknown key means the event belongs to another
 * service (pre-production-service listens on the same topic for its shot-list jobs and its rows
 * won't match our key format) and is silently dropped, matching the shot-list-consumer contract.
 *
 * <p>On SUCCESS, runs the existing parse -> critic -> save path in
 * {@link ProjectRequirementIdeaService#persistFromLlmResponse} (the same code the old sync
 * endpoint used to run inline) and flips the local job row to SUCCEEDED. On FAILED, records
 * the error and flips to FAILED. The UI's polling observes either terminal state on its next
 * poll.
 *
 * <p>No thread pool -- the Kafka consumer group is the concurrency mechanism, same shape as
 * llm-gateway's worker side and pre-production-service's shot-list ChatJobCompletedConsumer.
 * Consumer group is per-service (see {@code creative-planning.llm-gateway.job-completed-consumer
 * -group}) so this service's replicas cooperatively drain the same partitions without stepping
 * on pre-production-service's own consumers of the same topic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatJobCompletedConsumer {

    private final ObjectMapper objectMapper;
    private final IdeaGenerationJobRepository ideaGenerationJobRepository;
    private final ProjectRequirementIdeaService projectRequirementIdeaService;

    @KafkaListener(
            topics = "${creative-planning.llm-gateway.job-completed-topic}",
            groupId = "${creative-planning.llm-gateway.job-completed-consumer-group:creative-planning-service-llm-job-completed}"
    )
    public void onMessage(String payload) {
        ChatJobCompletedEvent event;
        try {
            event = objectMapper.readValue(payload, ChatJobCompletedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable ChatJobCompletedEvent payload: {}", ex.getMessage(), ex);
            return;
        }

        Optional<IdeaGenerationJob> maybeJob =
                ideaGenerationJobRepository.findByLlmJobIdempotencyKey(event.idempotencyKey());
        if (maybeJob.isEmpty()) {
            // Not one of ours -- pre-production-service's shot-list job (or a stale
            // idea-generation job that's already been reaped) owns this key. Silent drop.
            return;
        }
        IdeaGenerationJob job = maybeJob.get();

        if (event.status() == ChatJobCompletedEvent.Status.FAILED) {
            markFailed(job, event.errorMessage() == null
                    ? "llm-gateway reported FAILED with no message" : event.errorMessage());
            return;
        }
        try {
            handleSuccess(job, event);
        } catch (Exception ex) {
            // Post-LLM persistence failed (parse error, critic HTTP error, DB write failure) --
            // the LLM call itself already succeeded and was billed, so this must not re-throw as
            // retryable; terminate as FAILED with the local error so the UI stops polling and
            // shows a clean error rather than spinning forever. Same rationale as shot-list.
            log.error("Post-response persistence failed for idea-generation jobId={}", job.getId(), ex);
            markFailed(job, "Post-response persistence failed: " + ex.getMessage());
        }
    }

    @Transactional
    protected void handleSuccess(IdeaGenerationJob job, ChatJobCompletedEvent event) {
        List<IdeaOptionView> options = projectRequirementIdeaService.persistFromLlmResponse(
                job.getTenantId(), job.getProjectRequirementId(), event.response());
        markSucceeded(job);
        log.info("Idea-generation succeeded jobId={} requirementId={} optionCount={}",
                job.getId(), job.getProjectRequirementId(), options.size());
    }

    @Transactional
    protected void markSucceeded(IdeaGenerationJob job) {
        OffsetDateTime now = OffsetDateTime.now();
        job.setStatus(IdeaGenerationJobStatus.SUCCEEDED);
        job.setErrorMessage(null);
        job.setUpdatedAt(now);
        job.setCompletedAt(now);
        ideaGenerationJobRepository.save(job);
    }

    @Transactional
    protected void markFailed(IdeaGenerationJob job, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        job.setStatus(IdeaGenerationJobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(now);
        job.setCompletedAt(now);
        ideaGenerationJobRepository.save(job);
        log.warn("Idea-generation failed jobId={} requirementId={} reason={}",
                job.getId(), job.getProjectRequirementId(), errorMessage);
    }
}
