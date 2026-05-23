package com.dalai.llama.creator.dto.request;

import com.dalai.llama.creator.dto.response.GeneratedScriptResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SaveGeneratedScriptRequest(
        @Size(max = 240) String title,
        @Size(max = 20000) String script,
        @Min(15) @Max(10800) Integer durationSeconds,
        @Size(max = 64) String dialogueLanguage,
        @Size(max = 32) String screenType,
        @Valid GeneratedScriptResponse.CinematicScript scriptJson,
        @Valid List<GeneratedScriptResponse.CinematicShot> scenes
) {
}
