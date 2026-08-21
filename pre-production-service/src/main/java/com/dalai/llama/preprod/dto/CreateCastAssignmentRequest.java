package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateCastAssignmentRequest(
        @NotNull UUID scriptCharacterId,
        @NotNull UUID castProfileId,
        String wardrobeNote,
        String performanceDirection
) {
}
