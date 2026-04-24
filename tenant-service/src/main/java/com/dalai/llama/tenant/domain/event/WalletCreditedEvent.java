package com.dalai.llama.tenant.domain.event;

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
public class WalletCreditedEvent {
    private UUID tenantId;
    private UUID walletId;
    private UUID paymentId;
    private UUID subscriptionId;
    private BigDecimal amount;
    private BigDecimal totalBalance;
    private String currency;
    private Instant occurredAt;
    private String gateway;
}
