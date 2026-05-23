package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record GenerateProductionPlanRequest(
        @Size(max = 80) String styleKey,
        @Min(1) Integer focusedShotNumber
) {
}
