package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.VoiceIdentityType;

/** The effective clone identity after an idempotent set-if-absent operation. */
public record ClonedVoiceIdentityView(
        String clonedVoiceId,
        String providerId,
        VoiceIdentityType voiceIdentityType,
        boolean newlyPersisted
) {
}