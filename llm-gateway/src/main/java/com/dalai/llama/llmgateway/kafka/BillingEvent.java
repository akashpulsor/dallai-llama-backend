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
        String modelId,
        int inputTokens,
        int outputTokens,
        BigDecimal cost,
        String currency,
        String status,
        OffsetDateTime createdAt
) {
}
