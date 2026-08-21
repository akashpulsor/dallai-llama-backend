package com.dalai.llama.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record SendChatMessageRequest(
        @NotBlank String content
) {
}
