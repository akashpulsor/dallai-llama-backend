package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.MoodProfile;
import com.dalai.llama.preprod.domain.ShotSize;
import com.dalai.llama.preprod.domain.TimeOfDay;
import jakarta.validation.constraints.Positive;

/**
 * Hand-edit of a shot's plan -- the fields a creator actually reaches for when the AI-generated
 * shot list needs a manual correction (wrong action, wrong voice-over, wrong camera size, wrong
 * lighting mood, etc.). Same PATCH semantics used everywhere else in this service: every field is
 * nullable and only non-null fields are applied to the shot; the rest of the row is untouched.
 *
 * <p>Scope match with {@link com.dalai.llama.preprod.dto.LightingPlanEditRequest} on purpose --
 * a creator can fix a lighting plan by hand, and the same is now true for the shot plan itself
 * (the earlier surface was scriptLine + durationSeconds only, insufficient for real fixes).
 * Cinematography power-user fields ({@code cine_*}) stay in {@link
 * com.dalai.llama.preprod.dto.CameraPlanEditRequest} / {@code LightingPlanEditRequest} where they
 * already live -- this DTO is the practical hand-edit surface for the shot row itself, not a
 * catch-all edit for every column.
 */
public record UpdateShotRequest(
        String scriptLine,
        @Positive Integer durationSeconds,
        String action,
        String voiceOver,
        String emotion,
        String textOverlay,
        String soundDesign,
        String editingNotes,
        String location,
        TimeOfDay timeOfDay,
        MoodProfile lightingMood,
        ShotSize cameraShotSize,
        String cameraAngle,
        String cameraMovement,
        String cameraNote
) {
}
