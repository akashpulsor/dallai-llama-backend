package com.dalai.llama.preprod.kafka;

import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;

import java.util.UUID;

/**
 * Wire-compatible mirror of llm-gateway's {@code ChatJobCompletedEvent}. Same shape and enum
 * values -- both sides run with type-header stripping so field-name-based JSON matches by
 * position. Callers filter incoming events to their own by looking up the local job row via
 * {@link #idempotencyKey} (see
 * {@link com.dalai.llama.preprod.repository.ShotListJobRepository#findByLlmJobIdempotencyKey}).
 */
public record ChatJobCompletedEvent(
        UUID jobId,
        String tenantId,
        String idempotencyKey,
        Status status,
        LlmGatewayChatResponse response,
        String errorMessage
) {
    public enum Status {
        SUCCEEDED, FAILED
    }
}
