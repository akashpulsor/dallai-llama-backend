package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.TenantType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Entry point B: no brand exercise, no locked idea -- a user fills a brief directly (the "how
 * without a branding exercise user can start a project" path). */
public record CreateStandaloneRequirementRequest(
        @NotBlank String briefText,
        String targetAudience,
        String campaignDirection,
        @NotNull BudgetTier budgetTier,
        @NotNull TenantType tenantType
) {
}
