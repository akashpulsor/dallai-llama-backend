package com.dalai.llama.product.dto.creatorvideo;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record CreatorVideoPlanResponse(
        String planCode,
        String planName,
        String tier, // FREE or PROFESSIONAL
        String billingCycle, // null for the free plan
        BigDecimal price,
        String currency,
        CreatorVideoEntitlements entitlements
) {
}
