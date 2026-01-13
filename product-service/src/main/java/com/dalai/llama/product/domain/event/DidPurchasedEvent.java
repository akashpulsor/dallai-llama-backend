package com.dalai.llama.product.domain.event;


import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DidPurchasedEvent {

    private UUID tenantId;
    private UUID didId;

    private String number;          // E.164
    private String country;
    private String city;

    private String didwwDidId;

    private Instant occurredAt;
}
