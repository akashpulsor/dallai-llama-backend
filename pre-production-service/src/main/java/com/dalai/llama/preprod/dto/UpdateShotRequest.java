package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.Positive;

/** Hand-edit of a shot's script line and/or length -- the two fields creators actually change
 * after either the AI shot list or {@link CreateShotRequest} produced the row. Both nullable:
 * PATCH semantics, only a non-null field is applied, everything else on the shot is untouched. */
public record UpdateShotRequest(
        String scriptLine,
        @Positive Integer durationSeconds
) {
}
