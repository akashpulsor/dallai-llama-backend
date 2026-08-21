package com.dalai.llama.preprod.service.videogen;

import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;

import java.util.UUID;

public record GenerateShotRequest(
        UUID projectId,
        ShotContext shotContext,
        FeatureFlags featureFlagOverrides,
        boolean autoApprove
) {
}
