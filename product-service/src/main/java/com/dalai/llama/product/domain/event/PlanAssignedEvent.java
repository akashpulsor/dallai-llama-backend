package com.dalai.llama.product.domain.event;


import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanAssignedEvent {

    private UUID tenantId;
    private UUID planId;

    private String planCode;
    private String productCode;

    private Instant effectiveFrom;
    private Instant occurredAt;
}
