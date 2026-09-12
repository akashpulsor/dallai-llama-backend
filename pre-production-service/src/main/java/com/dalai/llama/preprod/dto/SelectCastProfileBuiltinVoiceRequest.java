package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import jakarta.validation.constraints.Size;

/** Provider voice identity selected from llm-gateway's {@code GET /v1/voices/builtin}. The picker
 * submits {@code providerVoiceId} as {@code clonedVoiceId} and {@code providerId} unchanged; both
 * are required because a provider voice id is only meaningful to its owning provider. */
public record SelectCastProfileBuiltinVoiceRequest(
        @NotNull UUID castProfileId,
        UUID projectId,
        @NotBlank @Size(max = 128) String clonedVoiceId,
        @NotBlank @Size(max = 64) String providerId
) {
}