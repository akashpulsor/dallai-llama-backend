package com.dalai.llama.preprod.dto;

import java.util.UUID;

/** Trusted tenant and route identity combined with the explicit voice selection payload. */
public record SelectCastProfileBuiltinVoiceCommand(
        UUID tenantId,
        UUID routeCastProfileId,
        UUID castProfileId,
        UUID projectId,
        String clonedVoiceId,
        String providerId
) {
}
