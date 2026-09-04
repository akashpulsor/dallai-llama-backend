package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** Only {@code orderIndex}/{@code startSeconds} are inherently about this beat's placement in the
 * shot's timeline -- the client always supplies those. {@code durationSeconds}/{@code text}/
 * {@code characterKey} already live on {@link com.dalai.llama.preprod.domain.entity.Shot} (as
 * planned by script/shot-list generation), so they're optional here: null means "use the shot's own
 * plan", and {@link com.dalai.llama.preprod.service.ShotDialogueBeatService} resolves them from the
 * Shot row rather than trusting whatever the UI happened to have in memory. A non-null value is a
 * genuine one-off override for this beat only -- it never writes back to the Shot, so the shot's own
 * plan stays the rollback target for free. */
public record SaveShotDialogueBeatRequest(
        @NotNull Integer orderIndex,
        @NotNull @PositiveOrZero BigDecimal startSeconds,
        @PositiveOrZero BigDecimal durationSeconds,
        String text,
        String characterKey
) {
}
