package com.dalai.llama.tenant.domain.event;

import com.dalai.llama.tenant.domain.entity.enums.BillingStateType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingStateChangedEvent {

    private UUID tenantId;
    private BillingStateType previousState;
    private BillingStateType currentState;
    private Instant occurredAt;
}
