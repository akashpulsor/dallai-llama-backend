package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One of storyboardTag's four lighting instruments (KEY/FILL/RIM/NEG). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LightPlacementView(
        @JsonProperty("role") String role,
        @JsonProperty("householdGearName") String householdGearName,
        @JsonProperty("professionalGearName") String professionalGearName,
        @JsonProperty("position") String position,
        @JsonProperty("distanceFeet") Double distanceFeet,
        @JsonProperty("angleDegrees") Double angleDegrees,
        @JsonProperty("modifier") String modifier
) {
    public String role() {
        return role == null ? "" : role;
    }

    public String position() {
        return position == null ? "" : position;
    }

    public String modifier() {
        return modifier == null ? "" : modifier;
    }
}
