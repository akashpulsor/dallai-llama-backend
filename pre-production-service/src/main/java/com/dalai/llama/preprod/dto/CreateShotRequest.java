package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ShotType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** Manually inserts one shot into an existing screenplay scene -- the creator-driven counterpart
 * to {@code ShotListGenerationService.generate()}'s AI-authored batch. Deliberately minimal:
 * every other field a Shot carries (camera plan, lighting, cinematography, ...) is left at its
 * generation-time default and can be filled in afterward through the same per-feature endpoints
 * an AI-generated shot uses (CameraPlanController, LightingPlanController, etc.) -- this endpoint
 * only needs to get a real row into the shot list, not replicate the AI's full richness by hand.
 *
 * <p>Note for callers: {@code ShotListGenerationService.generate()} deletes and replaces every
 * shot for the project on each run, so a manually-added shot does not survive a "regenerate shot
 * list" -- same as any hand-edit to an AI-authored shot would not. */
public record CreateShotRequest(
        @NotNull UUID screenplaySceneId,
        String scriptLine,
        @Positive Integer durationSeconds,
        ShotType shotType
) {
}
