package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record GenerateStoryboardRequest(
        @Size(max = 32) String screenType,
        @Min(300) @Max(604800) Long signedUrlTtlSeconds
) {
}
