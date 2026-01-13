package com.dalai.llama.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class VerifyPaymentRequest {

    @NotBlank
    private String gatewayOrderId;

    @NotBlank
    private String gatewayPaymentId;

    @NotBlank
    private String gatewaySignature;
}
