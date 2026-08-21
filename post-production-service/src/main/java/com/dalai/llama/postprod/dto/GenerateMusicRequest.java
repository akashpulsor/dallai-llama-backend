package com.dalai.llama.postprod.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateMusicRequest(
        @NotBlank String moodPrompt,
        Integer durationSeconds,
        String model
) {
}
