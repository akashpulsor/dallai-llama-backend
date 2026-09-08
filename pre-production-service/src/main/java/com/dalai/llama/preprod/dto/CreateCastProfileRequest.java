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
        /** Both nullable together for the "AI-generated identity" flow (no uploaded face,
         * relies on {@code builtinVoiceId} for the voice). The "real person likeness" flow
         * still requires both -- enforced client-side by CastProfileQuickCreate's mode toggle. */
        String faceRefBucket,
        String faceRefObjectKey,
        String description,
        Integer age,
        String gender,
        String voiceRefBucket,
        String voiceRefObjectKey,
        String builtinVoiceId
) {
}
