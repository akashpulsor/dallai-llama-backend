package com.dalai.llama.videogen.dto;

import java.util.UUID;

public record ProjectConfigView(
        UUID projectId,
        FeatureFlags defaultFlags,
        boolean autoApprove,
        String preferredVoiceCloneModel
) {
}
