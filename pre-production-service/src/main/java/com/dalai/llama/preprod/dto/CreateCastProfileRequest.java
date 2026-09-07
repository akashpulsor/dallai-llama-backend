package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.CastProfileType;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** {@code projectId == null} creates a reusable library entry, same convention as CastProfile
 * itself. {@code profileType} defaults to ACTOR when omitted; {@code age}/{@code gender}/
 * {@code voiceRefBucket}/{@code voiceRefObjectKey}/{@code builtinVoiceId} are meaningful for ACTOR
 * profiles only. {@code voiceRefBucket}+{@code voiceRefObjectKey} (a real sample) and {@code
 * builtinVoiceId} (a stock voice) are alternatives -- send at most one pair. */
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
        String voiceRefObjectKey,
        String builtinVoiceId
) {
}
