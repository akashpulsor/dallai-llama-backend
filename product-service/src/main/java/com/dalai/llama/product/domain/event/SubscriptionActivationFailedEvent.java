package com.dalai.llama.product.domain.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SubscriptionActivationFailedEvent {
    private UUID subscriptionId;
    private UUID tenantId;
    private String reason;
}