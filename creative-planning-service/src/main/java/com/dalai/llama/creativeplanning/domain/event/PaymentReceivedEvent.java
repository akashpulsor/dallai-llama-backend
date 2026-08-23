package com.dalai.llama.creativeplanning.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Local mirror of billing-service's {@code PaymentReceivedEvent} (topic
 * {@code billing.payment.received}) -- this codebase's convention is each service owns its own
 * copy of an event DTO rather than sharing one across modules (see product-service's own copy of
 * tenant-service's {@code ProvisioningCompletedEvent} for precedent). Only {@code tenantId} and
 * {@code projectRequirementId} are actually consumed here; the rest is kept for parity /
 * forward-compatibility with the producer's shape.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReceivedEvent {
    private UUID tenantId;
    private UUID paymentId;
    private UUID subscriptionId;
    private UUID projectRequirementId;
    private BigDecimal amount;
    private String currency;
    private Instant occurredAt;
    private String gateway;
}
