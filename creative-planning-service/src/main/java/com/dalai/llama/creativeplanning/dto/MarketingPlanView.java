package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.MarketingPlanStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MarketingPlanView(
        UUID id,
        UUID brandContextId,
        UUID productProfileId,
        String targetAudienceInput,
        BudgetTier budgetTier,
        MarketingPlanStatus status,
        String executiveSummary,
        String marketAnalysis,
        String targetAudienceProfile,
        String positioningStatement,
        String brandStrategy,
        String marketingObjectives,
        String channelStrategy,
        String contentStrategy,
        String budgetGuidance,
        String successMetrics,
        String risksAndMitigations,
        String referencedCaseStudyPatterns,
        OffsetDateTime createdAt
) {
}
