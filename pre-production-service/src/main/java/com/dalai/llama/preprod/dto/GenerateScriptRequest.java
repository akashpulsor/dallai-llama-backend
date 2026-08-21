package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code briefText} is the locked idea's brief/concept text. In this v1 slice the caller passes
 * it directly rather than this service fetching it from creative-planning-service (that
 * cross-service read is a named follow-up, not yet built -- see PreProductionOrchestration notes). */
public record GenerateScriptRequest(
        @NotBlank String briefText,
        Integer targetDurationSeconds
) {
}
