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
public class DidPurchasedEvent {

    private UUID tenantId;
    private UUID didId;

    private String number;          // E.164
    private String country;
    private String city;

    private String didwwDidId;

    private Instant occurredAt;
}
