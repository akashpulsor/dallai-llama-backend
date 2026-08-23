package com.dalai.llama.billing.domain.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReceivedEvent {

    private UUID tenantId;
    private UUID paymentId;
    private UUID subscriptionId;
    /** Non-null when this payment funds a creative-planning-service ProjectRequirement --
     * the field consumers should filter on to react to a funded brief. */
    private UUID projectRequirementId;
    private BigDecimal amount;
    private String currency;
    private Instant occurredAt;
    private String gateway;
}
