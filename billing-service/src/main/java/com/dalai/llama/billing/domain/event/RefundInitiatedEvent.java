package com.dalai.llama.billing.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundInitiatedEvent {
    private UUID tenantId;
    private UUID paymentId;
    private UUID subscriptionId;
    private BigDecimal amount;
    private String refundId;
    private String reason;
    private String trigger;
    private Instant occurredAt;
}
