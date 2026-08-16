package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of storyboardTag.primaryCharacters/sideCharacters - a full CharacterRenderSpec. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterRenderSpecView(
        @JsonProperty("storyCharacterName") String storyCharacterName,
        @JsonProperty("archetypeLabel") String archetypeLabel,
        @JsonProperty("age") Integer age,
        @JsonProperty("gender") String gender,
        @JsonProperty("ethnicity") String ethnicity,
        @JsonProperty("bodyType") String bodyType,
        @JsonProperty("heightImpression") String heightImpression,
        @JsonProperty("hair") HairSpecView hair,
        @JsonProperty("distinguishingFeatures") String distinguishingFeatures,
        @JsonProperty("wardrobeThisShot") String wardrobeThisShot,
        @JsonProperty("postureBaseline") String postureBaseline,
        /** Which cast profile/actor is assigned to this character - resolved from characterCastMappings at generation time. */
        @JsonProperty("assignedActorName") String assignedActorName,
        @JsonProperty("assignedActorVisualProfile") String assignedActorVisualProfile
) {
    public String storyCharacterName() {
        return storyCharacterName == null ? "" : storyCharacterName;
    }

    public String assignedActorName() {
        return assignedActorName == null ? "" : assignedActorName;
    }

    public String wardrobeThisShot() {
        return wardrobeThisShot == null ? "" : wardrobeThisShot;
    }

    public String distinguishingFeatures() {
        return distinguishingFeatures == null ? "" : distinguishingFeatures;
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record HairSpecView(
        @JsonProperty("style") String style,
        @JsonProperty("length") String length,
        @JsonProperty("color") String color
) {
}
