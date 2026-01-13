package com.dalai.llama.billing.domain.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletLowBalanceEvent {

    private UUID tenantId;
    private BigDecimal balance;
    private BigDecimal threshold;
    private Instant occurredAt;
}
