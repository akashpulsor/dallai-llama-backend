package com.dalai.llama.billing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
public class RechargeWalletRequest {

    @NotNull
    @DecimalMin(value = "1.00", inclusive = true)
    private BigDecimal amount;
    private String currency;
    private UUID subscriptionId;
}
