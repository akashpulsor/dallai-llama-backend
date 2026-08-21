package com.dalai.llama.critic.dto.shotcontext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Inbound wire-contract copy of pre-production-service's assembled {@code ShotContext} -- this
 * is what the harness actually critiques. */
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
