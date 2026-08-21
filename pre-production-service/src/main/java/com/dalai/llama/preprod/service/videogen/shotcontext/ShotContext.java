package com.dalai.llama.preprod.service.videogen.shotcontext;

import java.util.List;

/** Outbound wire-contract copy of video-generation-service's real {@code ShotContext} --
 * assembled by {@code ShotContextAssemblyService}, never authored by hand elsewhere. */
public record ShotContext(
        String shotRef,
        Narrative narrative,
        List<Character> characters,
        Environment environment,
        Lighting lighting,
        Camera camera,
        ProductBrand productBrand,
        Technical technical,
        List<ContinuityAnchor> continuityAnchors,
        AudioAmbience audioAmbience
) {
}
