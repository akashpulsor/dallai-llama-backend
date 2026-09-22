package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.BudgetTier;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LockedIdeaView(
        UUID id,
        UUID sessionId,
        /** The originating brief -- non-null when this idea was locked from the requirement/brief
         * flow (share-token brief), null when it came from a campaign-planning chat session. The
         * creator's project workspace uses this to hydrate a "Brief" panel from the requirement's
         * text/audience/product/reference-images without needing a separate reverse-lookup call. */
        UUID projectRequirementId,
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
