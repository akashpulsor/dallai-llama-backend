package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateCastProfileVoiceRequest(
        @NotBlank String voiceRefBucket,
        @NotBlank String voiceRefObjectKey
) {
}
