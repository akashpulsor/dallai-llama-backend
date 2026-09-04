package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.BudgetTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Entry point into this service -- always created from a locked idea handed off by
 * creative-planning-service (see the design doc's §9.6 {@code POST /v1/projects/from-locked-idea}). */
public record CreateProjectRequest(
        @NotNull UUID lockedIdeaId,
        @NotBlank String name,
        @NotNull BudgetTier budgetTier,
        // Optional: how many client review rounds are included before the paywall. Set on the
        // new-project tab; null -> the default (2) applied in ProjectService.createFromLockedIdea.
        Integer reviewAllowance
) {
}
