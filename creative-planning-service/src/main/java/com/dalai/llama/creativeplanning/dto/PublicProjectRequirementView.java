package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;

/** What an external, unauthenticated viewer (someone holding a share link, not a tenant user)
 * sees -- deliberately excludes tenant id, created-by, and any internal cross-references {@link
 * ProjectRequirementView} carries. */
public record PublicProjectRequirementView(
        String briefText,
        String targetAudience,
        String campaignDirection,
        BudgetTier budgetTier,
        boolean funded
) {
}
