package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.VoiceIdentityType;

import java.util.UUID;

public record CastProfileView(
        UUID id,
        UUID projectId,
        CastProfileType profileType,
        String displayName,
        String faceRefBucket,
        String faceRefObjectKey,
        String faceRefUrl,
        String description,
        Integer age,
        String gender,
        String voiceRefBucket,
        String voiceRefObjectKey,
        String builtinVoiceId,
        String clonedVoiceId,
        String clonedVoiceProviderId,
        VoiceIdentityType voiceIdentityType,
        long projectCount
) {
}
