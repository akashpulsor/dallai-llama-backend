package com.dalai.llama.llmgateway.kafka;

import com.dalai.llama.llmgateway.dto.ChatResponse;

import java.util.UUID;

/**
 * Terminal outcome of a job originally submitted via {@link ChatJobRequestedEvent}. Published
 * exactly once per submission -- either {@code status=SUCCEEDED} with {@code response} populated,
 * or {@code status=FAILED} with {@code errorMessage} populated. Callers own their own consumer
 * of {@code llm.job.completed} and filter to jobIds they issued (either via {@code tenantId} +
 * their own job-tracking table, or via a caller-supplied correlation ID pushed through in
 * {@link ChatJobRequestedEvent#idempotencyKey}).
 */
public record ChatJobCompletedEvent(
        UUID jobId,
        String tenantId,
        String idempotencyKey,
        Status status,
        ChatResponse response,
        String errorMessage
) {
    public enum Status {
        SUCCEEDED, FAILED
    }

    public static ChatJobCompletedEvent succeeded(UUID jobId, String tenantId, String idempotencyKey, ChatResponse response) {
        return new ChatJobCompletedEvent(jobId, tenantId, idempotencyKey, Status.SUCCEEDED, response, null);
    }

    public static ChatJobCompletedEvent failed(UUID jobId, String tenantId, String idempotencyKey, String errorMessage) {
        return new ChatJobCompletedEvent(jobId, tenantId, idempotencyKey, Status.FAILED, null, errorMessage);
    }
}
