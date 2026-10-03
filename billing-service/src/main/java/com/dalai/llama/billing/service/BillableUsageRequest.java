package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BillableUsageRequest(
        UUID tenantId,
        UUID projectId,
        UsageMetric metric,
        BigDecimal quantity,
        BillingUnit unit,
        BigDecimal unitCost,
        BigDecimal totalCost,
        String sourceType,
        UUID sourceId,
        String description,
        UUID subscriptionId,
        String idempotencyKey,
        String currency,
        Instant recordedAt,
        /** Raw facts from llm-gateway: what the call was for, and what kind of model ran it.
         * Billing turns these into a stage; neither is required. */
        String taskKey,
        String modelType,
        /** Provider cost before billing's margin, in {@code currency}. Null = no margin was
         * applied, so the raw cost is {@code totalCost} itself. */
        BigDecimal rawCost
) {

    /** Pre-rawCost arity -- a caller that applies no margin. */
    public BillableUsageRequest(UUID tenantId, UUID projectId, UsageMetric metric, BigDecimal quantity,
                                BillingUnit unit, BigDecimal unitCost, BigDecimal totalCost, String sourceType,
                                UUID sourceId, String description, UUID subscriptionId, String idempotencyKey,
                                String currency, Instant recordedAt, String taskKey, String modelType) {
        this(tenantId, projectId, metric, quantity, unit, unitCost, totalCost, sourceType, sourceId,
                description, subscriptionId, idempotencyKey, currency, recordedAt, taskKey, modelType, null);
    }

    /** Pre-taskKey arity, so existing callers construct unchanged. */
    public BillableUsageRequest(UUID tenantId, UUID projectId, UsageMetric metric, BigDecimal quantity,
                                BillingUnit unit, BigDecimal unitCost, BigDecimal totalCost, String sourceType,
                                UUID sourceId, String description, UUID subscriptionId, String idempotencyKey,
                                String currency, Instant recordedAt) {
        this(tenantId, projectId, metric, quantity, unit, unitCost, totalCost, sourceType, sourceId,
                description, subscriptionId, idempotencyKey, currency, recordedAt, null, null, null);
    }
}
