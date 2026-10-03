package com.dalai.llama.billing.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Where one project's money came from and went. {@code production} is the price exactly as the
 * customer was shown it (null for a project that never had a duration-priced quote).
 * <ul>
 *   <li>{@code customer} -- what the client actually paid: the brief's upfront amount plus every
 *       captured payment on the review page (lock balance, extra review rounds).</li>
 *   <li>{@code creator} -- what reached the creator's wallet from those payments, what the project's
 *       AI usage was charged to that wallet, and the difference as the creator's profit.</li>
 *   <li>{@code platform} -- what the providers actually charged, the margin on top of it, and the
 *       platform's share of review-page payments. Ops-only: {@link #forCreator} drops it.</li>
 * </ul>
 */
public record ProjectEconomics(
        UUID projectId,
        String currency,
        ProductionCharges production,
        Customer customer,
        Creator creator,
        Platform platform
) {

    public record Customer(BigDecimal paidUpfront, BigDecimal paidOnReview, BigDecimal totalPaid) {}

    public record Creator(BigDecimal received, BigDecimal productionCharged, BigDecimal profit) {}

    public record Platform(BigDecimal providerCost, BigDecimal charged, BigDecimal usageMargin,
                           BigDecimal reviewPaymentShare, BigDecimal profit) {}

    /** The creator's own view of the same project: no provider cost, no platform take. */
    public ProjectEconomics forCreator() {
        return new ProjectEconomics(projectId, currency, production, customer, creator, null);
    }
}
