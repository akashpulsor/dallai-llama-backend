package com.dalai.llama.creativeplanning.dto;

import jakarta.validation.constraints.NotBlank;

/** Explicit action, not an inline chat edit -- mirrors the "chat suggests, an explicit call
 * commits" discipline used elsewhere in this system. {@code instructions} is typically drawn
 * from what the user asked for in the plan's chat, but stated explicitly here. */
public record ReviseMarketingPlanRequest(
        @NotBlank String instructions
) {
}
