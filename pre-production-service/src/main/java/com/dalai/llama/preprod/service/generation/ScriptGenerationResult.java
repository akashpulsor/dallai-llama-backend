package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Shape of the JSON llm-gateway's PRE_PROD_SCRIPT_GENERATE task returns -- parsed, never
 * carried around as a raw {@code Map}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScriptGenerationResult(
        String scriptText,
        String pacingStyle,
        String emotionalArc,
        String hookStrategy,
        Boolean noHumans,
        String logline,
        String centralConflict,
        String endingPayoff,
        String setting,
        String hook,
        String storytellingType,
        List<CharacterItem> characters
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CharacterItem(
            String characterKey,
            String characterName,
            String characterRole,
            String description,
            String characterType,
            String gender,
            Integer age,
            String ageRange,
            String look,
            String profile,
            String persona,
            String backstory,
            String motivation,
            String fearOrBlock,
            String relationshipToStory,
            String speakingStyle,
            String visualIdentity
    ) {
    }
}
