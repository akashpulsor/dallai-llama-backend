package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** {@code brandContextId} picks which of the tenant's (possibly several) brands this plan is for
 * -- required even when {@code productProfileId} is also given, so a mismatched pick fails fast.
 * {@code productProfileId} is null for a brand-wide plan (not scoped to one product).
 * {@code targetAudienceInput} lets this specific plan target a narrower/different audience than
 * the brand's own default -- e.g. one plan per market segment. */
public record GenerateMarketingPlanRequest(
        @NotNull UUID brandContextId,
        UUID productProfileId,
        @NotBlank String targetAudienceInput,
        @NotNull BudgetTier budgetTier
) {
}
