package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkspaceChatRequest(
        @NotBlank
        @Size(max = 24000)
        String message,
        @Min(1)
        Integer shotNumber
) {
}
