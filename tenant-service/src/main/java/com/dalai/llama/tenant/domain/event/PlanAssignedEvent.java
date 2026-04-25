package com.dalai.llama.tenant.domain.event;

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
public class PlanAssignedEvent {

    private UUID tenantId;
    private UUID planId;

    private String planCode;
    private String productCode;

    private Instant effectiveFrom;
    private Instant occurredAt;
}
