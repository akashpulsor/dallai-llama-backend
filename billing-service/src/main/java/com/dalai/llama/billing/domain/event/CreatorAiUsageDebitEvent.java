package com.dalai.llama.billing.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorAiUsageDebitEvent {

    private UUID eventId;
    private UUID tenantId;
    private String userId;
    private UUID projectId;
    private UUID generationJobId;
    private UUID promptRunId;
    private String promptType;
    private String provider;
    private String model;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private BigDecimal tokenRate;
    private String rateUnit;
    private BigDecimal amount;
    private String currency;
    private String description;
    private OffsetDateTime occurredAt;
    private Map<String, Object> tokenMetadata;
    private Map<String, Object> costMetadata;
}
