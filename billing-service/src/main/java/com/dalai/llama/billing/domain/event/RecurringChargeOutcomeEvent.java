package com.dalai.llama.billing.domain.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published only for {@code RecurringCharge}s that carry a {@code subscriptionId} (today: type
 * {@code SUBSCRIPTION}, e.g. creator-video plan renewals) -- PBX recurring charges (PLATFORM_FEE/
 * DID_RENTAL/AGENT_FEE) don't need a per-charge outcome event since their own billing-state
 * machinery already reacts to balance changes. Whichever product owns {@code subscriptionId}
 * decides what a failed/succeeded renewal means for its own entitlements; billing-service only
 * reports the fact of the charge outcome, nothing about what to do with it.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringChargeOutcomeEvent {

    private UUID recurringChargeId;
    private UUID tenantId;
    private UUID subscriptionId;
    private String chargeType;
    private BigDecimal amount;
    private boolean succeeded;
    /** Null when {@code succeeded} is true. */
    private String failureReason;
    private Instant occurredAt;
}
