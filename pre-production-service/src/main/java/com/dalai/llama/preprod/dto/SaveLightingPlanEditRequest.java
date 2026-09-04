package com.dalai.llama.preprod.dto;

/** A manual correction to an existing lighting plan -- no LLM call, just applies onto the live
 * row (source flips to EDITED). Every field optional: only what the creator actually changed needs
 * to be sent, anything omitted keeps its current value. */
public record SaveLightingPlanEditRequest(
        String cinematicIntent,
        Integer estimatedSetupMinutes,
        String keyLightGear,
        String fillLightGear,
        String rimLightGear,
        String negFillGear,
        String diffuserGear,
        String cameraRigGear,
        String buildSteps
) {
}
