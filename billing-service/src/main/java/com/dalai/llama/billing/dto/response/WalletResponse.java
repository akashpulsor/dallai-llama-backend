package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class WalletResponse {
    private UUID walletId;
    private UUID tenantId;
    private BigDecimal balance;
    private String currency;
    private BigDecimal creditLimit;
    private BigDecimal lowBalanceThreshold;
    private Boolean autoRechargeEnabled;
    private BigDecimal autoRechargeThreshold;
    private BigDecimal autoRechargeAmount;
    private Instant lastRechargedAt;
}
