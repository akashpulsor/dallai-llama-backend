package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** {@code brandContextId} picks which of the tenant's (possibly several) brands this session is
 * for -- required even when {@code productProfileId} is also given, so a mismatched pick (a
 * product from a different brand) fails fast at creation rather than silently mixing brands.
 * {@code productProfileId} is null for a brand-level session (before a specific product is
 * chosen). */
public record CreateCampaignSessionRequest(
        @NotNull UUID brandContextId,
        UUID productProfileId,
        @NotNull BudgetTier budgetTier
) {
}
