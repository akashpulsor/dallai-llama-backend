package com.dalai.llama.videogen.dto.shotcontext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Doc §2a Rules 1-7: strongly typed everywhere, no maps, nullable = "not populated by the
 * caller" (never absence-of-meaning). Field set is scoped to what {@code ProviderRequestBuilder}
 * in creator-service actually uses today, not the source doc's full 11-branch cinematography
 * tree -- new fields land the same way (additive, nullable) as real demand shows up.
 */
public record ShotContext(
        @NotBlank String shotRef,
        @Valid Narrative narrative,
        List<@Valid Character> characters,
        @Valid Environment environment,
        @Valid Lighting lighting,
        @Valid Camera camera,
        @Valid ProductBrand productBrand,
        @Valid Technical technical,
        List<@Valid ContinuityAnchor> continuityAnchors,
        @Valid AudioAmbience audioAmbience
) {
}
