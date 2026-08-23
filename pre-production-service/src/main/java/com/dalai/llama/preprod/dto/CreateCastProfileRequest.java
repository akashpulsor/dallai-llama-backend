package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.CastProfileType;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** {@code projectId == null} creates a reusable library entry, same convention as CastProfile
 * itself. {@code profileType} defaults to ACTOR when omitted; {@code age}/{@code gender}/
 * {@code voiceRefBucket}/{@code voiceRefObjectKey} are meaningful for ACTOR profiles only. */
public record CreateCastProfileRequest(
        UUID projectId,
        CastProfileType profileType,
        @NotBlank String displayName,
        @NotBlank String faceRefBucket,
        @NotBlank String faceRefObjectKey,
        String description,
        Integer age,
        String gender,
        String voiceRefBucket,
        String voiceRefObjectKey
) {
}
