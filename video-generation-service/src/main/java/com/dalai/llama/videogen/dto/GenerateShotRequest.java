package com.dalai.llama.videogen.dto;

import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GenerateShotRequest(
        @NotNull UUID projectId,
        @NotNull @Valid ShotContext shotContext,
        FeatureFlags featureFlagOverrides,
        boolean autoApprove
) {
}
