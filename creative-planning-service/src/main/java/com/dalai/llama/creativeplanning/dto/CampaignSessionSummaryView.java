package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.CampaignSessionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CampaignSessionSummaryView(
        UUID id,
        CampaignSessionStatus status,
        BudgetTier budgetTier,
        int messageCount,
        LockedIdeaView lockedIdea,
        OffsetDateTime createdAt
) {
}
