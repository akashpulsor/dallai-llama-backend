package com.dalai.llama.preprod.dto;

import java.util.UUID;

public record MotionGraphicPlanView(
        UUID id,
        UUID shotId,
        String concept,
        String onScreenText,
        String visualStyle,
        String animationNotes,
        Integer durationSeconds
) {
}
