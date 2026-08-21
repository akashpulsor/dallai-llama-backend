package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.TimeOfDay;

import java.util.UUID;

public record ScreenplaySceneView(
        UUID id,
        Integer sceneNumber,
        String slug,
        String location,
        TimeOfDay timeOfDay,
        String summary,
        String characterFocus,
        String emotionalPurpose
) {
}
