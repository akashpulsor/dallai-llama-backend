package com.dalai.llama.product.dto.creatorvideo;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
public record CreatorVideoSubscriptionResponse(
        UUID subscriptionId,
        String status, // Subscription status, or "INSUFFICIENT_BALANCE" (mirrors the PBX subscribe response shape)
        String planCode,
        String planName,
        String billingCycle,
        BigDecimal price,
        String currency,
        Instant currentPeriodEnd,
        // Populated only when status = INSUFFICIENT_BALANCE, same recharge-prompt shape used elsewhere.
        BigDecimal currentWalletBalance,
        BigDecimal shortFallAmount
) {
}
