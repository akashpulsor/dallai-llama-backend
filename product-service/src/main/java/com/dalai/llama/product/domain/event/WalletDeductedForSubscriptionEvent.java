package com.dalai.llama.product.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletDeductedForSubscriptionEvent(
        UUID eventId,
        UUID subscriptionId,
        UUID tenantId,
        UUID paymentId,
        BigDecimal subscriptionAmount,
        BigDecimal walletTopUp,
        BigDecimal totalDeducted,
        String planCode,
        Instant deductedAt
) {}