package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record GenerateStoryScriptRequest(
        @Min(15) @Max(60) Integer durationSeconds,
        @Size(max = 64) String categoryCode,
        @Size(max = 4000) String idea,
        @Size(max = 64) String dialogueLanguage,
        @Size(max = 32) String screenType,
        Map<String, Object> context
) {
}
