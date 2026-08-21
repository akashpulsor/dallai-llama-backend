package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LockedIdeaView(
        UUID id,
        UUID sessionId,
        String title,
        String concept,
        String targetAudience,
        String campaignAngle,
        String keyMessage,
        String tone,
        BudgetTier budgetTier,
        OffsetDateTime createdAt
) {
}
