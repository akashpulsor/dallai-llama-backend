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
        OffsetDateTime createdAt,
        // Included client review rounds before the paywall (set on the new-project tab; default 2).
        int reviewAllowance,
        // Creator's on/off switch for client reviews (false = reviews closed for this project).
        boolean reviewsEnabled,
        // Creator's manual gate for the client's ability to download the assembled final video --
        // see Project.finalVideoDownloadUnlocked. Preview is always allowed; this gates download.
        boolean finalVideoDownloadUnlocked
) {
}
