package com.dalai.llama.billing.domain.event;

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
public class SubscriptionActivationFailedEvent {
    private UUID eventId;
    private UUID subscriptionId;
    private UUID tenantId;

    /** Wallet debit transaction reference — billing-service uses this to refund. */
    private UUID paymentId;

    /** Saga step that failed (e.g., "DID_PROVISIONED", "TENANT_TRUNK_CREATED"). */
    private String failedAtStep;

    /** Exception message / reason for failure. */
    private String reason;

    private Instant failedAt;
}
