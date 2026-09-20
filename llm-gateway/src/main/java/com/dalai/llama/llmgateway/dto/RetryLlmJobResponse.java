package com.dalai.llama.llmgateway.dto;

import java.util.UUID;

/**
 * Admin retry endpoint's response. Confirms the job is being re-dispatched: the row was reset,
 * a fresh {@code llm.job.requested} event was published, and callers should now poll their own
 * job-tracking row (in pre-production-service's case, {@code shot_list_job}) for the next
 * terminal state.
 */
public record RetryLlmJobResponse(
        UUID jobId,
        String idempotencyKey,
        String previousStatus,
        String message
) {
}
