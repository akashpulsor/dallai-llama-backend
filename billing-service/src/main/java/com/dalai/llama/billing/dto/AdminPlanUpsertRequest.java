package com.dalai.llama.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body for create + update on /api/v1/internal/admin/plans. The code field is immutable after
 * create -- update ignores it. Frequency accepts only MONTHLY today (product decision -- the
 * db column supports the other cycles when the product decision changes; the controller enforces
 * this here so an operator can't add a QUARTERLY plan through the UI even by hand-crafting the
 * request body).
 */
public record AdminPlanUpsertRequest(
        @NotBlank @Size(min = 2, max = 60) @Pattern(regexp = "^[A-Z0-9_-]+$",
                message = "code must be uppercase letters/digits/underscore/dash only") String code,
        @NotBlank @Size(min = 2, max = 200) String name,
        @Size(max = 1000) String description,
        @NotNull @DecimalMin(value = "0.00", message = "amount must be >= 0") BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank @Pattern(regexp = "MONTHLY",
                message = "only MONTHLY plans are supported today") String frequency,
        @NotNull @DecimalMin(value = "0.00", message = "walletCreditPerCycle must be >= 0")
        BigDecimal walletCreditPerCycle,
        Boolean isDefault,
        Boolean active
) {
}
