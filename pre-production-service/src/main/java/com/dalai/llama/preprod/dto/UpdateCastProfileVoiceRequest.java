package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.VoiceIdentityType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdateCastProfileVoiceRequest(
        @NotNull UUID castProfileId,
        UUID projectId,
        @NotNull VoiceIdentityType voiceIdentityType,
        String voiceRefBucket,
        String voiceRefObjectKey,
        @Size(max = 128) String clonedVoiceId,
        @Size(max = 64) String providerId
) {
}
