package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.TenantType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectRequirementView(
        UUID id,
        TenantType tenantType,
        UUID lockedIdeaId,
        String briefText,
        String targetAudience,
        String campaignDirection,
        BudgetTier budgetTier,
        String shareToken,
        OffsetDateTime shareTokenExpiresAt,
        boolean funded,
        UUID fundedBy,
        OffsetDateTime createdAt
) {
}
