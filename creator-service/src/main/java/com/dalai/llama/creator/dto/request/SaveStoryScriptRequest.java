package com.dalai.llama.creator.dto.request;

import com.dalai.llama.creator.dto.response.GeneratedStoryScriptResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record SaveStoryScriptRequest(
        @Size(max = 240) String title,
        @Min(15) @Max(60) Integer durationSeconds,
        @Size(max = 64) String dialogueLanguage,
        @Size(max = 32) String screenType,
        @Size(max = 20000) String scriptText,
        @Valid GeneratedStoryScriptResponse.StoryScript scriptJson
) {
}
