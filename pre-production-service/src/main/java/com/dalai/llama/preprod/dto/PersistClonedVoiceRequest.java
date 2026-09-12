package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.VoiceIdentityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Internal command to claim the durable provider identity created for a cast profile. */
public record PersistClonedVoiceRequest(
        @NotBlank @Size(max = 128) String clonedVoiceId,
        @NotBlank @Size(max = 64) String providerId,
        @NotNull VoiceIdentityType voiceIdentityType
) {
}