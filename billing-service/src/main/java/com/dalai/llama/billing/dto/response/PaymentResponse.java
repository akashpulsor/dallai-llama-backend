package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class PaymentResponse {
    private UUID paymentId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String gateway;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private String failureReason;
    private String description;
    private Instant createdAt;
}
