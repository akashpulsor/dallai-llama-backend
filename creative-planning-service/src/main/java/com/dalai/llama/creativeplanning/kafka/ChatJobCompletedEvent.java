package com.dalai.llama.creativeplanning.kafka;

import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatResponse;

import java.util.UUID;

/**
 * Wire-compatible mirror of llm-gateway's {@code ChatJobCompletedEvent} -- same shape and enum
 * values so Jackson matches by field position. This service's {@link ChatJobCompletedConsumer}
 * filters incoming events to its own by looking up the local {@code idea_generation_job} row via
 * {@link #idempotencyKey}; anything without a match belongs to another service (pre-production
 * -service also listens on the same topic for its shot-list jobs) and is silently dropped.
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
