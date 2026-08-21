package com.dalai.llama.creativeplanning.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateBrandContextRequest(
        @NotBlank String brandName,
        String industry,
        String brandVoice,
        String targetAudience,
        String brandValues
) {
}
