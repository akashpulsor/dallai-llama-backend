package com.dalai.llama.product.domain.event;


import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DidReleasedEvent {

    private UUID tenantId;
    private UUID didId;

    private String number;
    private String reason;

    private Instant releasedAt;
    private Instant occurredAt;
}
