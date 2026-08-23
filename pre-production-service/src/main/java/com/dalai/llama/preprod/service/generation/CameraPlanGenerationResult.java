package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shape of the JSON llm-gateway's PRE_PROD_CAMERA_PLAN_GENERATE task returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CameraPlanGenerationResult(
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
