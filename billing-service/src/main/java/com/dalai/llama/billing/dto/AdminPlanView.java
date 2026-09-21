package com.dalai.llama.billing.dto;

import com.dalai.llama.billing.domain.entity.RatePlan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Ops-facing plan summary. Same shape the Plans tab in /admin renders. Kept as a projection of
 * RatePlan so future fields (line-item rate cards, promo pricing) can land on the entity without
 * breaking the DTO contract with the frontend.
 */
public record AdminPlanView(
        UUID id,
        String code,
        String name,
        String description,
        boolean isDefault,
        boolean active,
        BigDecimal amount,
        String currency,
        String frequency,
        BigDecimal walletCreditPerCycle,
        Instant createdAt,
        Instant updatedAt
) {
    public static AdminPlanView from(RatePlan p) {
        return new AdminPlanView(
                p.getId(), p.getCode(), p.getName(), p.getDescription(),
                p.isDefault(), p.isActive(),
                p.getAmount(), p.getCurrency(), p.getFrequency(), p.getWalletCreditPerCycle(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
