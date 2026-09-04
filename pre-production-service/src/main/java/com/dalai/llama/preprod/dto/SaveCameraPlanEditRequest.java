package com.dalai.llama.preprod.dto;

/** A manual correction to an existing camera plan -- no LLM call, just applies onto the live row
 * (source flips to EDITED). Every field optional: only what the creator actually changed needs to
 * be sent, anything omitted keeps its current value. */
public record SaveCameraPlanEditRequest(
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
