package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.JobStatus;
import com.dalai.llama.llmgateway.domain.entity.LlmJob;
import com.dalai.llama.llmgateway.dto.ChatRequest;
import com.dalai.llama.llmgateway.dto.RetryLlmJobResponse;
import com.dalai.llama.llmgateway.dto.StuckLlmJobView;
import com.dalai.llama.llmgateway.kafka.ChatJobRequestedEvent;
import com.dalai.llama.llmgateway.kafka.ChatJobRequestedPublisher;
import com.dalai.llama.llmgateway.repository.LlmJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read + admin actions on {@link LlmJob} for the ops dashboard.
 *
 * <p>Design notes:
 * <ul>
 *   <li>The retry path deliberately reuses the same {@code llm.job.requested} topic normal
 *       callers publish on -- so the existing {@link com.dalai.llama.llmgateway.kafka.ChatJobRequestedConsumer}
 *       pipeline (idempotency, wallet-check, provider dispatch, cost recording, terminal
 *       {@code llm.job.completed} publish) runs unchanged. No side pipeline for admin retries.</li>
 *   <li>The stored {@link LlmJob#getRequestContent()} is the exact {@link ChatRequest} JSON that
 *       was sent originally -- reconstructing the event from that guarantees a retry is a true
 *       replay, not a re-derivation that could pick up a since-changed prompt template.</li>
 *   <li>Row is reset to PENDING and last_error cleared before publish, so the consumer's own
 *       idempotency lookup finds an in-progress record and does not create a duplicate.</li>
 * </ul>
 *
 * <p>Auth: the endpoint that calls this lives under {@code /api/v1/internal/admin/**}. Its
 * gateway-level protection (oauth2-proxy + Keycloak {@code dalai_admin} role) is the perimeter;
 * this service does not re-check the role.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLlmJobService {

    /** What the "stuck" list shows: anything in-flight (may have crashed), anything terminal-
     * retryable (an operator wants to see recent failures and click retry). COMPLETED intentionally
     * excluded -- 90% of the list would otherwise be noise. */
    private static final Set<JobStatus> STUCK_OR_RETRYABLE_STATUSES =
            EnumSet.of(JobStatus.PROCESSING, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.TIMED_OUT);

    /** Every status for the "all jobs" listing. */
    private static final Set<JobStatus> ALL_STATUSES = EnumSet.allOf(JobStatus.class);

    private final LlmJobRepository jobRepository;
    private final ChatJobRequestedPublisher requestedPublisher;
    private final ObjectMapper objectMapper;

    /** All jobs in a non-terminal or FAILED state within the given lookback window. Newest first
     * so the dashboard's default view surfaces recent problems, not ancient ones. */
    @Transactional(readOnly = true)
    public List<StuckLlmJobView> listStuck(Duration lookback) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(lookback);
        return jobRepository
                .findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(STUCK_OR_RETRYABLE_STATUSES, cutoff)
                .stream()
                .map(StuckLlmJobView::from)
                .toList();
    }

    /** Every job (all statuses, including COMPLETED) within the lookback window. This is the
     * "did this ever run" view -- a creator asking "did my shot-list generation actually happen"
     * shows up here alongside successes and failures. Newest first. */
    @Transactional(readOnly = true)
    public List<StuckLlmJobView> listAll(Duration lookback) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(lookback);
        return jobRepository
                .findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(ALL_STATUSES, cutoff)
                .stream()
                .map(StuckLlmJobView::from)
                .toList();
    }

    /**
     * Re-run a job by publishing a fresh {@link ChatJobRequestedEvent} for it. Only allowed from a
     * retryable terminal state ({@link JobStatus#isRetryable()} -- FAILED / CANCELLED / TIMED_OUT)
     * so an in-flight PROCESSING job is never double-dispatched, and a COMPLETED job is not
     * silently re-billed.
     *
     * <p>Does NOT mutate the row directly. LlmGatewayService.chat() (invoked by the consumer that
     * picks up the new event) is idempotent on the idempotency key and knows how to re-dispatch
     * a retryable terminal row -- reset to PROCESSING, increment attempt_count, publish
     * completion afterwards. Duplicating that state transition here would create two writers on
     * the same row.
     */
    @Transactional(readOnly = true)
    public RetryLlmJobResponse retry(UUID jobId) {
        LlmJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No LLM job " + jobId));
        JobStatus previous = job.getStatus();
        if (previous == JobStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Job " + jobId + " is still PROCESSING -- refuse to double-dispatch; wait for it to terminate first");
        }
        if (previous == JobStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Job " + jobId + " already COMPLETED -- rerunning would overwrite result_content. Create a new job instead.");
        }
        if (!previous.isRetryable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Job " + jobId + " is in " + previous + " which JobStatus#isRetryable() does not accept -- refuse to retry.");
        }

        ChatRequest request = decodeRequest(job);
        ChatJobRequestedEvent event = new ChatJobRequestedEvent(job.getTenantId(), job.getIdempotencyKey(), request);
        requestedPublisher.publish(event);

        log.info("Admin retry published jobId={} tenantId={} idempotencyKey={} previousStatus={}",
                jobId, job.getTenantId(), job.getIdempotencyKey(), previous);
        return new RetryLlmJobResponse(jobId, job.getIdempotencyKey(), previous.name(),
                "Re-dispatched to llm.job.requested; poll llm_job.status for the next terminal state");
    }

    private ChatRequest decodeRequest(LlmJob job) {
        String raw = job.getRequestContent();
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Job " + job.getJobId() + " has no stored request_content -- cannot reconstruct the request to retry.");
        }
        try {
            return objectMapper.readValue(raw, ChatRequest.class);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored request_content for job " + job.getJobId() + " is not a valid ChatRequest JSON", ex);
        }
    }
}
