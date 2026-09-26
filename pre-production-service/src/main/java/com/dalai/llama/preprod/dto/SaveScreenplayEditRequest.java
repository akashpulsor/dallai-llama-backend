package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.SceneType;
import com.dalai.llama.preprod.domain.TimeOfDay;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** A creator's manually-edited scene list -- saved as a new EDITED version rather than
 * overwriting the version it was edited from (see ScreenplayGenerationService.saveEdit). No LLM
 * call involved; this is a direct write. */
public record SaveScreenplayEditRequest(
        @NotEmpty List<@NotNull SceneInput> scenes
) {
    public record SceneInput(
            @NotNull Integer sceneNumber,
            String slug,
            String location,
            TimeOfDay timeOfDay,
            String summary,
            String characterFocus,
            String emotionalPurpose,
            Integer estimatedSeconds,
            /** Creator-set: this scene needs a multi-image reference bundle. Phase 1: persisted
             * on the scene; phase 2 propagates to shots + reveals upload UI. Null preserves the
             * server-side default (false). */
            Boolean needsMultiImage,
            /** Optional label ("app flow", "before/after") the multi-image bundle should be
             * called. Null keeps whatever's already saved (or null). */
            String multiImageLabel,
            /** Creator-set structural intent. Null preserves the existing value (or leaves it
             * null -- downstream reads null as GENERIC). */
            SceneType sceneType
    ) {
    }
}
