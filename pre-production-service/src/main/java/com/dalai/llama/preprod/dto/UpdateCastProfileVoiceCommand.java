package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.VoiceIdentityType;
import java.util.UUID;

/** Tenant and route identity come from the controller, never from the JSON body. */
public record UpdateCastProfileVoiceCommand(
        UUID tenantId,
        UUID routeCastProfileId,
        UUID castProfileId,
        UUID projectId,
        VoiceIdentityType voiceIdentityType,
        String voiceRefBucket,
        String voiceRefObjectKey,
        String clonedVoiceId,
        String providerId
) {}
