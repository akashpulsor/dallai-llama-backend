package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record GenerateProductionPlanRequest(
        @Size(max = 80) String styleKey,
        @Min(1) Integer focusedShotNumber,
        Boolean forceRegenerate,
        @Size(max = 80) String videoProvider,
        @Size(max = 160) String videoModel,
        @Min(1) Integer maxClipSeconds
) {
}
