package com.dalai.llama.tenant.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** billing.payment.received, as billing-service publishes it; {@code projectRequirementId} is set
 * when the payment funded a brief. */
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
