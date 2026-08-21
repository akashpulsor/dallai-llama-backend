package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.BudgetTier;
import com.dalai.llama.preprod.domain.ProjectStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectView(
        UUID id,
        String name,
        UUID lockedIdeaId,
        BudgetTier budgetTier,
        ProjectStatus status,
        OffsetDateTime createdAt
) {
}
