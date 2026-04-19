package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class PaymentEventResponse {
    private UUID id;
    private UUID paymentId;
    private String fromStatus;
    private String toStatus;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private String reason;
    private String source;
    private Instant createdAt;
}