package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SwitchLockedIdeaRequest(
        @NotNull UUID lockedIdeaId
) {
}
