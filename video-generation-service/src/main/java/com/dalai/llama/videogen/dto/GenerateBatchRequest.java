package com.dalai.llama.videogen.dto;

import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record GenerateBatchRequest(
        @NotEmpty List<@Valid ShotContext> shots,
        FeatureFlags featureFlagOverrides
) {
}
