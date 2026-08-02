package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WorkspaceRevertRequest(
        @NotNull
        @Min(1)
        Integer toVersion
) {
}
