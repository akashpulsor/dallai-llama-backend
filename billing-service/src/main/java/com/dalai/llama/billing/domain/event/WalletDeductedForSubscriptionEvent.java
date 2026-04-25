package com.dalai.llama.billing.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletDeductedForSubscriptionEvent {
    private UUID eventId;
    private  UUID subscriptionId;
    private UUID tenantId;
    private UUID paymentId;
    private BigDecimal subscriptionAmount;
    private BigDecimal walletTopUp;
    private BigDecimal totalDeducted;
    private String planCode;
    private Instant deductedAt;

}