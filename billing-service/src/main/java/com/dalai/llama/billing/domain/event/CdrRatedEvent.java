package com.dalai.llama.billing.domain.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CdrRatedEvent {

    private UUID tenantId;
    private UUID cdrId;
    private BigDecimal callCost;
    private BigDecimal aiCost;
    private BigDecimal totalCost;
    private Instant occurredAt;
}
