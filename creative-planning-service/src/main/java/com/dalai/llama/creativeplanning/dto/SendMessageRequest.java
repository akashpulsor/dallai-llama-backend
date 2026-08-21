package com.dalai.llama.creativeplanning.dto;

import jakarta.validation.constraints.NotBlank;

public record SendMessageRequest(
        @NotBlank String content
) {
}
