package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScreenplayGenerationResult(
        List<SceneItem> scenes
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SceneItem(
            Integer sceneNumber,
            String slug,
            String location,
            String timeOfDay,
            String summary,
            String characterFocus,
            String emotionalPurpose
    ) {
    }
}
