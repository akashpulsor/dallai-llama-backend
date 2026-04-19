package com.dalai.llama.billing.domain.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionActivatedEvent {
    private UUID tenantId;
    private UUID subscriptionId;
    private UUID tenantAppId;
    private UUID paymentId;
    private BigDecimal planAmount;
    private BigDecimal walletCredit;
    private Instant occurredAt;
}