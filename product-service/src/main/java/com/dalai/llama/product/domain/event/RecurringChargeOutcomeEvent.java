package com.dalai.llama.product.domain.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Structural copy of billing-service's event of the same name (no shared module between
 * services -- see {@code LlmBillingEvent}'s javadoc in billing-service for why this pattern is
 * already established here). Only consumed for subscriptions this product recognizes; any other
 * product's subscriptionId simply won't be found in {@code subscriptions} and is ignored. */
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
    private String failureReason;
    private Instant occurredAt;
}
