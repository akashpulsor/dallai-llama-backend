package com.dalai.llama.billing.dto.request;

import com.dalai.llama.billing.domain.entity.enums.PaymentMethodType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class AddPaymentMethodRequest {

    @NotNull
    private PaymentMethodType type;

    private String displayName;
    private String maskedReference;
}
