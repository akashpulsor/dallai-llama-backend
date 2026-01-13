package com.dalai.llama.billing.dto.response;

import com.dalai.llama.billing.domain.entity.enums.BillingStateType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class BillingStateResponse {
    private BillingStateType state;
    private Instant graceExpiresAt;
    private String blockReason;
    private Instant blockedAt;
    private Instant lastCheckedAt;
}
