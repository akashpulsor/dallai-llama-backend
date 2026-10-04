package com.dalai.llama.videogen.dto.generationplan;

import com.dalai.llama.videogen.domain.ShotActionKind;

import java.math.BigDecimal;

/** An action the approved shot plan requires. {@code fixedSeconds} is set only for an action whose
 * length is not negotiable (a measured line of dialogue); null means its timing is flexible. */
public record RequiredActionView(
        String actionId,
        ShotActionKind kind,
        String description,
        BigDecimal fixedSeconds,
        String dependsOnActionId
) {
}
