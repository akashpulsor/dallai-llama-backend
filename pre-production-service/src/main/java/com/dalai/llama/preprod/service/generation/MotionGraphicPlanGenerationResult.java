package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shape of the JSON llm-gateway's PRE_PROD_MOTION_GRAPHIC_PLAN_GENERATE task returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MotionGraphicPlanGenerationResult(
        String concept,
        String onScreenText,
        String visualStyle,
        String animationNotes,
        Integer durationSeconds
) {
}
