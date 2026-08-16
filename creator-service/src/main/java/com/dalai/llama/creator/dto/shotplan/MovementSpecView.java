package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** cameraPlanSheetTag.movementSpec - see {@link StoryboardTagView} for the read-view contract. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovementSpecView(
        @JsonProperty("moveType") String moveType,
        @JsonProperty("startPosition") String startPosition,
        @JsonProperty("endPosition") String endPosition,
        @JsonProperty("speed") String speed,
        @JsonProperty("stabilizationRequired") Boolean stabilizationRequired,
        @JsonProperty("stabilizationTool") String stabilizationTool,
        @JsonProperty("rigType") String rigType,
        @JsonProperty("liveCameraMove") Boolean liveCameraMove,
        @JsonProperty("operatorCue") String operatorCue
) {
    public String moveType() {
        return moveType == null ? "" : moveType;
    }

    public String speed() {
        return speed == null ? "" : speed;
    }
}
