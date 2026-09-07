package com.dalai.llama.llmgateway.kafka;

import com.dalai.llama.llmgateway.dto.ChatRequest;

/**
 * Async job submission -- caller (pre-production-service, later video-generation-service, etc.)
 * publishes this to {@code llm.job.requested}; llm-gateway's worker consumes, calls the provider,
 * publishes a matching {@link ChatJobCompletedEvent} to {@code llm.job.completed} on either
 * success or failure. The caller owns the domain-side follow-up (parsing, persisting) and its own
 * consumer of {@code llm.job.completed}.
 *
 * <p>{@code idempotencyKey} is the same field the existing sync {@link
 * com.dalai.llama.llmgateway.controller.InternalLlmGatewayController#chat} takes -- it's the
 * uniqueness anchor on the {@code llm_job} table, so a duplicate {@code
 * ChatJobRequestedEvent} (Kafka retry, caller redelivery) is safe: the worker re-uses the
 * existing job row rather than creating a second one.
 */
public record ChatJobRequestedEvent(
        String tenantId,
        String idempotencyKey,
        ChatRequest request
) {
}
