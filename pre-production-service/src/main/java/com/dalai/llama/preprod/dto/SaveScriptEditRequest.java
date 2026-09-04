package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

/** A creator's manually-edited script -- saved as a new EDITED version rather than overwriting
 * the version it was edited from (see ScriptGenerationService#saveEdit). No LLM call involved;
 * this is a direct write. Only {@code scriptText} is required -- the narrative meta fields
 * (pacing, logline, etc.) are optional and left as they were on the parent version when omitted,
 * since a manual edit is usually just a prose fix, not a full rewrite of every field. */
public record SaveScriptEditRequest(
        @NotBlank String scriptText,
        String pacingStyle,
        String emotionalArc,
        String hookStrategy,
        Boolean noHumans,
        String logline,
        String centralConflict,
        String endingPayoff,
        String setting,
        String hook,
        String storytellingType
) {
}
