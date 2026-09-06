package com.dalai.llama.preprod.dto;

/** The subset of a shot's fields the "post-production handoff" project list actually renders --
 * camera metadata plus each of the 3 generated images a creator visually recognizes a shot by
 * (storyboard sketch, lighting build sheet, camera plan). Any of the three URLs may be null if
 * that image was never generated for the shot yet -- creator-ui's normalizer already tolerates a
 * missing thumbnail. */
public record ShotDesignSummaryView(
        Integer shotNumber,
        String cameraAngle,
        String cameraMovement,
        String lensSuggestion,
        String storyboardImageUrl,
        String lightingImageUrl,
        String cameraPlanImageUrl
) {
}
