package com.dalai.llama.preprod.dto;

import java.util.UUID;

public record LightingPlanView(
        UUID id,
        UUID shotId,
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
