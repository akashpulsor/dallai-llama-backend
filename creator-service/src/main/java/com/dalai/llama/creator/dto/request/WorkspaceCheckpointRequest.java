package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkspaceCheckpointRequest(
        @NotBlank
        @Size(max = 255)
        String title
) {
}
