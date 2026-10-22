package com.dalai.llama.creativeplanning.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** The price a pre-production project was sold at, as raw requirement fields -- billing-service
 * owns what to do with them (spend cap, the lock balance), so nothing here is pre-computed.
 * {@code requiredAmount} is what the brief asked for upfront; it was only actually paid when
 * {@code funded} is true. {@code requirementId} is the brief itself, which the upfront payment
 * was made against. */
public record ProjectQuoteView(
        Integer durationSeconds,
        BigDecimal quotedPlatformCost,
        BigDecimal quotedTotalPrice,
        BigDecimal quotedCreatorMarginPercent,
        String quotedCurrency,
        BigDecimal requiredAmount,
        boolean funded,
        UUID requirementId
) {
}
