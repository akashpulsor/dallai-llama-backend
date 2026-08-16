package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** lightingBuildSheetTag.perspectiveView - see {@link StoryboardTagView} for the read-view contract. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PerspectiveView(
        @JsonProperty("narrativeDescription") String narrativeDescription,
        @JsonProperty("visibleElements") List<String> visibleElements
) {
    public String narrativeDescription() {
        return narrativeDescription == null ? "" : narrativeDescription;
    }
}
