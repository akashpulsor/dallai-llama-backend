package com.dalai.llama.billing.dto.response;

import com.dalai.llama.billing.domain.entity.CreatorPayout;
import com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One project's payout with the figures it was worked out from:
 * {@code amount = clientPaid - productionCharged - gatewayFees}, capped at the wallet balance. */
public record CreatorPayoutView(
        UUID id,
        UUID tenantId,
        UUID projectId,
        CreatorPayoutStatus status,
        BigDecimal clientPaid,
        BigDecimal productionCharged,
        BigDecimal gatewayFees,
        BigDecimal amount,
        String currency,
        int attempts,
        String gatewayPayoutId,
        String utr,
        String failureReason,
        Instant createdAt,
        Instant paidAt
) {
    public static CreatorPayoutView of(CreatorPayout payout) {
        return new CreatorPayoutView(payout.getId(), payout.getTenantId(), payout.getProjectId(), payout.getStatus(),
                payout.getClientPaid(), payout.getProductionCharged(), payout.getGatewayFees(), payout.getAmount(),
                payout.getCurrency(), payout.getAttempts(), payout.getGatewayPayoutId(), payout.getUtr(),
                payout.getFailureReason(), payout.getCreatedAt(), payout.getPaidAt());
    }
}
