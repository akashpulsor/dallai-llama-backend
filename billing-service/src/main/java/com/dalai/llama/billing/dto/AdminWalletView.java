package com.dalai.llama.billing.dto;

import com.dalai.llama.billing.domain.entity.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Ops-dashboard read view of a tenant's wallet. Same fields as the Wallet entity minus the
 * bookkeeping (id, version, updatedAt): what an operator needs to answer "how much does this
 * tenant have, is auto-recharge configured, when was it last topped up". */
public record AdminWalletView(
        UUID tenantId,
        BigDecimal balance,
        String currency,
        BigDecimal creditLimit,
        BigDecimal lowBalanceThreshold,
        Boolean autoRechargeEnabled,
        BigDecimal autoRechargeThreshold,
        BigDecimal autoRechargeAmount,
        Instant lastRechargedAt,
        Instant lastDeductedAt
) {
    public static AdminWalletView from(Wallet w) {
        return new AdminWalletView(
                w.getTenantId(), w.getBalance(), w.getCurrency(),
                w.getCreditLimit(), w.getLowBalanceThreshold(),
                w.getAutoRechargeEnabled(), w.getAutoRechargeThreshold(), w.getAutoRechargeAmount(),
                w.getLastRechargedAt(), w.getLastDeductedAt());
    }
}
