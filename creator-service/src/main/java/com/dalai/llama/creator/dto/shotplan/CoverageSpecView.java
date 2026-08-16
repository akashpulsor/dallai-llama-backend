package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** cameraPlanSheetTag.coverageSpec - see {@link StoryboardTagView} for the read-view contract. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageSpecView(
        @JsonProperty("coverageType") String coverageType,
        @JsonProperty("coverageContext") String coverageContext,
        @JsonProperty("companionShots") List<String> companionShots,
        @JsonProperty("editorIntent") String editorIntent
) {
    public String editorIntent() {
        return editorIntent == null ? "" : editorIntent;
    }
}
