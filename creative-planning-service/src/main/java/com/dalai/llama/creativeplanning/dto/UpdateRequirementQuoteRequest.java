package com.dalai.llama.creativeplanning.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** A creator overriding their own quote before the client pays -- see {@code
 * ProjectRequirementService#updateQuote}. Refused once the requirement is funded, same as every
 * other pre-funding-only mutation on this resource. */
public record UpdateRequirementQuoteRequest(
        @NotNull @Positive BigDecimal totalPrice,
        @NotNull @Min(1) @Max(100) Integer requiredPaymentPercent
) {
}
