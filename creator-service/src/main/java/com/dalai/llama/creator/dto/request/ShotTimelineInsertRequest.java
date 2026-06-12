package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShotTimelineInsertRequest(
        @Min(0) Integer afterShotNumber,
        @NotBlank @Size(max = 1200) String instruction,
        @Size(max = 160) String title,
        @Min(1) @Max(60) Integer durationSeconds,
        @Size(max = 32) String screenType,
        @Min(300) @Max(604800) Long signedUrlTtlSeconds
) {
}
