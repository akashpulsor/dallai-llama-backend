package com.dalai.llama.critic.dto;

import com.dalai.llama.critic.dto.shotcontext.ShotContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CritiqueRequest(
        @NotNull UUID projectId,
        @NotNull UUID shotId,
        @NotNull @Valid ShotContext shotContext
) {
}
