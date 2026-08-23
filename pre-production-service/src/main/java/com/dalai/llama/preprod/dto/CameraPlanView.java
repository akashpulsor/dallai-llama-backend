package com.dalai.llama.preprod.dto;

import java.util.UUID;

public record CameraPlanView(
        UUID id,
        UUID shotId,
        String blockingMap,
        String executionSteps,
        Boolean gimbalEnabled,
        String gimbalDevice,
        String gimbalMode,
        String gimbalPanSpeed,
        String gimbalTiltSpeed,
        String safetyFlags,
        Boolean requiresCoordinator,
        String complianceNote
) {
}
