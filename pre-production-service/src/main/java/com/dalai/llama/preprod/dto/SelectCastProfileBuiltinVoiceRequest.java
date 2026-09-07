package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code builtinVoiceId} is llm-gateway's {@code BuiltinVoiceView.providerVoiceId} -- handed over
 * as-is from a prior {@code GET /v1/voices/builtin} call, not re-validated against that catalog
 * here (same "trust a value obtained from a prior real call" convention as {@link
 * UpdateCastProfileVoiceRequest}'s bucket/objectKey). */
public record SelectCastProfileBuiltinVoiceRequest(
        @NotBlank String builtinVoiceId
) {
}
