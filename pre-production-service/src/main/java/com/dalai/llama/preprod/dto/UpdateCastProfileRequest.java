package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

/** Full replace of a cast profile's editable identity (Cast Library "Edit"). Voice has its own
 * endpoint. A null face pair keeps the current face; a new pair replaces it. Age and gender only
 * apply to ACTOR profiles and are ignored on a PRODUCT. */
public record UpdateCastProfileRequest(
        @NotBlank String displayName,
        String description,
        Integer age,
        String gender,
        String faceRefBucket,
        String faceRefObjectKey
) {
}
