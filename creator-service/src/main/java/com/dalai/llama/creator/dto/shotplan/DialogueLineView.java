package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** storyboardTag.primaryDialogue - the first spoken line in this shot, if any. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DialogueLineView(
        @JsonProperty("characterName") String characterName,
        @JsonProperty("archetypeLabel") String archetypeLabel,
        @JsonProperty("line") String line,
        @JsonProperty("deliveryNote") String deliveryNote,
        @JsonProperty("subtext") String subtext,
        @JsonProperty("lineStartTime") Double lineStartTime,
        @JsonProperty("lineEndTime") Double lineEndTime
) {
    public String line() {
        return line == null ? "" : line;
    }

    public String characterName() {
        return characterName == null ? "" : characterName;
    }

    public boolean isSilent() {
        return line().isBlank();
    }
}
