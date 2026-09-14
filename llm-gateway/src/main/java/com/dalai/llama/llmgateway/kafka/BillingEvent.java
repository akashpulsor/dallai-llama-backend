package com.dalai.llama.llmgateway.kafka;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Doc §9: emitted to {@code llm.billing.events} on job completion so a downstream billing
 * service can debit tenant wallets, the same pattern already used for the CCaaS subscription
 * saga. v1 publishes this directly, post-commit -- the {@code billing_event_outbox}
 * transactional-outbox hardening from doc §17 is deferred.
 */
public record BillingEvent(
        UUID eventId,
        UUID jobId,
        String tenantId,
        UUID projectId,
        String modelId,
        int inputTokens,
        int outputTokens,
        BigDecimal cost,
        String currency,
        String status,
        OffsetDateTime createdAt,
        /** What the call was for (prompt template task key), and what kind of model ran it.
         * Raw facts: the consumer decides which stage of the pipeline they belong to. Either can
         * be null -- a call with no template has no task key, and a model that cannot be routed
         * has no type. */
        String taskKey,
        String modelType
) {

    /** Pre-taskKey arity, so an older publisher still constructs. */
    public BillingEvent(UUID eventId, UUID jobId, String tenantId, UUID projectId, String modelId,
                        int inputTokens, int outputTokens, BigDecimal cost, String currency,
                        String status, OffsetDateTime createdAt) {
        this(eventId, jobId, tenantId, projectId, modelId, inputTokens, outputTokens, cost, currency,
                status, createdAt, null, null);
    }
}
