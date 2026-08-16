package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** lightingBuildSheetTag.floorPlan - see {@link StoryboardTagView} for the read-view contract. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FloorPlanView(
        @JsonProperty("roomDescription") String roomDescription,
        @JsonProperty("actor") FloorPlanActorView actor,
        @JsonProperty("keyLight") LightPlacementView keyLight,
        @JsonProperty("fillLight") LightPlacementView fillLight,
        @JsonProperty("rimLight") LightPlacementView rimLight,
        @JsonProperty("negFill") LightPlacementView negFill,
        @JsonProperty("camera") FloorPlanCameraView camera,
        @JsonProperty("compassNote") String compassNote
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record FloorPlanActorView(
        @JsonProperty("characterName") String characterName,
        @JsonProperty("facingDirection") String facingDirection
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record FloorPlanCameraView(
        @JsonProperty("rigDescription") String rigDescription,
        @JsonProperty("distanceFeet") Double distanceFeet,
        @JsonProperty("heightFeet") Double heightFeet
) {
}
