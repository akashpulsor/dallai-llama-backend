package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class CdrResponse {
    private UUID id;
    private String callId;
    private String direction;
    private String fromNumber;
    private String toNumber;
    private String didNumber;
    private Instant initiatedAt;
    private Instant answeredAt;
    private Instant endedAt;
    private int durationSeconds;
    private int billableSeconds;
    private String status;
    private String destinationType;
    private BigDecimal appliedRate;
    private BigDecimal callCost;
    private int aiSttSeconds;
    private int aiLlmTokens;
    private BigDecimal aiCost;
    private BigDecimal totalCost;
}
