package com.dalai.llama.preprod.dto;

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
            Integer estimatedSeconds
    ) {
    }
}
