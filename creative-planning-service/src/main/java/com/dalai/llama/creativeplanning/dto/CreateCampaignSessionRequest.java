package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** {@code productProfileId} is null for a brand-level session (before a specific product is
 * chosen). */
public record CreateCampaignSessionRequest(
        UUID productProfileId,
        @NotNull BudgetTier budgetTier
) {
}
