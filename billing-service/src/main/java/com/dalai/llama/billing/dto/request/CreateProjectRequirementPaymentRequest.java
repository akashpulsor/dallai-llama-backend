package com.dalai.llama.billing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.math.BigDecimal;

/** Body for funding one creative-planning-service ProjectRequirement -- mirrors
 * {@link CreatePaymentRequest} (the general wallet top-up shape) plus an optional currency
 * override, since a per-project amount is more likely to be quoted in a specific currency
 * than a wallet recharge is. */
@Getter
public class CreateProjectRequirementPaymentRequest {

    @NotNull
    @DecimalMin(value = "1.00", inclusive = true)
    private BigDecimal amount;

    private String currency;

    private String description;
}
