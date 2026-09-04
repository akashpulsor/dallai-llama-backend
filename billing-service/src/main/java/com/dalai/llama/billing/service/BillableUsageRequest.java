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
        Instant recordedAt
) {
}
