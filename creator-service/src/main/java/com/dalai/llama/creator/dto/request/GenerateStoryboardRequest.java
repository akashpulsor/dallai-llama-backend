package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public record GenerateStoryboardRequest(
        @Size(max = 32) String screenType,
        @Min(300) @Max(604800) Long signedUrlTtlSeconds,
        @Size(max = 80) String videoProvider,
        @Size(max = 160) String videoModel,
        @Min(1) @Max(60) Integer maxClipSeconds,
        @Size(max = 12000) String imagePrompt,
        Boolean productLed,
        @Size(max = 4000) String productReferenceDetails,
        @Size(max = 9) List<@Size(max = 4096) String> productReferenceImageUrls
) {
}
