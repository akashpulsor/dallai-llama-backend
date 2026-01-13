package com.dalai.llama.product.domain.event;


import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanChangedEvent {

    private UUID tenantId;

    private UUID oldPlanId;
    private String oldPlanCode;

    private UUID newPlanId;
    private String newPlanCode;

    private Instant effectiveFrom;
    private Instant occurredAt;
}
