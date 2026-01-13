package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class UsageSummaryResponse {
    private UUID tenantId;
    private Instant periodStart;
    private Instant periodEnd;
    private Map<String, BigDecimal> usageByMetric;
    private BigDecimal totalCost;
}
