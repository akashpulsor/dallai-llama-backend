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
            String emotionalPurpose,
            Integer estimatedSeconds,
            /** Real character keys (from the script's characters[]) present in this scene -- empty
             * or absent means a pure motion-graphic/B-roll beat with no character in it. */
            List<String> characterKeys
    ) {
    }
}
