package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.TimeOfDay;

import java.util.List;
import java.util.UUID;

public record ScreenplaySceneView(
        UUID id,
        Integer sceneNumber,
        String slug,
        String location,
        TimeOfDay timeOfDay,
        String summary,
        String characterFocus,
        String emotionalPurpose,
        Integer estimatedSeconds,
        /** Real characters present in this scene (via screenplay_scene_character) -- empty means a
         * pure motion-graphic/B-roll beat with no character in it. */
        List<SceneCharacterView> characters
) {
}
