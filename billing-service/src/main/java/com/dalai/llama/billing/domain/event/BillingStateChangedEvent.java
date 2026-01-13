package com.dalai.llama.billing.domain.event;

import com.dalai.llama.billing.domain.entity.enums.BillingStateType;
import lombok.*;

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
