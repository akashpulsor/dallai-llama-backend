package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shape of the JSON llm-gateway's PRE_PROD_LIGHTING_PLAN_GENERATE task returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LightingPlanGenerationResult(
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
