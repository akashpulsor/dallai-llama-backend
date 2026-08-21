package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** {@code projectId == null} creates a reusable library entry, same convention as CastProfile
 * itself. */
public record CreateCastProfileRequest(
        UUID projectId,
        @NotBlank String displayName,
        @NotBlank String faceRefBucket,
        @NotBlank String faceRefObjectKey,
        String description
) {
}
