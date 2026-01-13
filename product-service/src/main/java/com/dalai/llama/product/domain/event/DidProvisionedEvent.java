package com.dalai.llama.product.domain.event;


import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DidProvisionedEvent {

    private UUID tenantId;
    private UUID didId;

    private String number;
    private UUID sipTrunkId;
    private UUID sipEndpointId;

    private Instant provisionedAt;
    private Instant occurredAt;
}
