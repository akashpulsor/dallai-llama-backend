package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.CampaignSessionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CampaignSessionView(
        UUID id,
        UUID brandContextId,
        UUID productProfileId,
        BudgetTier budgetTier,
        CampaignSessionStatus status,
        OffsetDateTime createdAt
) {
}
