package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.TenantType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code quotedPlatformCost}/{@code quotedCreatorMarginPercent}/{@code quotedTotalPrice} are the
 * price breakdown snapshotted at creation time (platform per-second rate x duration, plus this
 * creator's own margin -- see {@code BillingServiceClient}); all null for a requirement created
 * from a locked idea (entry point A), which has no duration/language concept yet. */
public record ProjectRequirementView(
        UUID id,
        TenantType tenantType,
        UUID lockedIdeaId,
        UUID brandContextId,
        String briefText,
        String targetAudience,
        String campaignDirection,
        Integer durationSeconds,
        List<String> languages,
        BigDecimal quotedPlatformCost,
        BigDecimal quotedCreatorMarginPercent,
        BigDecimal quotedTotalPrice,
        String quotedCurrency,
        // What percentage of quotedTotalPrice the client must pay to unlock the project (100 =
        // full payment, the default), and the actual amount that resolves to -- see
        // ProjectRequirement#getRequiredAmount.
        int requiredPaymentPercent,
        BigDecimal requiredAmount,
        String shareToken,
        OffsetDateTime shareTokenExpiresAt,
        boolean funded,
        UUID fundedBy,
        // Null until this standalone requirement's generated ideas have had one locked (see
        // ProjectRequirementIdeaService#lockOption) -- once set, the requirement has become a real
        // pre-production Project at this id, reachable at /projects/{lockedProjectId} instead of
        // the brief workspace. Distinct from `lockedIdeaId` above, which is entry point A's own
        // "created from an existing locked idea" reference, not this reverse lookup.
        UUID lockedProjectId,
        OffsetDateTime createdAt,
        // Null until the client edits this brief through the public share link -- "the client
        // updated this brief" signal for the requirement list, see ProjectRequirement's javadoc.
        OffsetDateTime clientUpdatedAt
) {
}
