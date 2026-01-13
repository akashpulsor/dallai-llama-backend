package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class PaymentMethodResponse {
    private UUID id;
    private String type;
    private String displayName;
    private String maskedReference;
    private boolean isDefault;
    private Instant expiresAt;
}
