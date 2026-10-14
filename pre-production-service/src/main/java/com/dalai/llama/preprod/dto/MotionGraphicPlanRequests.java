package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

/** What a creator sends to change a motion graphic's plan. */
public final class MotionGraphicPlanRequests {

    private MotionGraphicPlanRequests() {
    }

    /** The plan as the creator wrote it. Every field is replaced: blank clears it. */
    public record Edit(
            String concept,
            String onScreenText,
            String visualStyle,
            String animationNotes
    ) {
    }

    /** What the creator wants improved -- "make the tagline land last, punchier style". */
    public record Enhance(@NotBlank String instruction) {
    }
}
