package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.SceneType;
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
        /** Creator-set: this scene needs a multi-image reference bundle uploaded (e.g. app flow,
         * before/after, product angles). Phase 1 -- flag rides here, actual upload UI lives on
         * the shot page in phase 2. Default false. */
        Boolean needsMultiImage,
        /** Optional label ("app flow", "before/after") for the multi-image bundle. Null when
         * needsMultiImage is false. */
        String multiImageLabel,
        /** Creator-set structural intent (IDENTITY / MOTION_GRAPHIC / LIVE_ACTION / PRODUCT_HERO /
         * GENERIC). Nullable on older rows; UI shows GENERIC when null. */
        SceneType sceneType,
        /** Real characters present in this scene (via screenplay_scene_character) -- empty means a
         * pure motion-graphic/B-roll beat with no character in it. */
        List<SceneCharacterView> characters
) {
}
