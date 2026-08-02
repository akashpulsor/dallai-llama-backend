package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HumanWorkOrderMessageRequest(
        @NotBlank @Size(max = 4000) String message,
        @Size(max = 40) String authorRole
) {
}
