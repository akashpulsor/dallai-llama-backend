package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

public record SendPublicChatMessageRequest(
        @NotBlank String content
) {
}
