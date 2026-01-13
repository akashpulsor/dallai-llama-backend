package com.dalai.llama.billing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class CreatePaymentRequest {

    @NotNull
    @DecimalMin(value = "1.00", inclusive = true)
    private BigDecimal amount;

    private String description;
}
