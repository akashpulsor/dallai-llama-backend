package com.dalai.llama.videogen.service.sceneenergy;

import java.util.Map;

/** What a {@link SceneEnergyStrategy} decided for one beat-dub call: {@code text} is the (possibly
 * unmodified) dialogue text actually sent to synthesis -- a future inline-tag strategy would embed
 * directives here (e.g. {@code "[excited] ..."} ) -- and {@code extraTtsParams} folds straight into
 * {@code BeatDubbingService}'s existing {@code ttsParams} map at the one unavoidable boundary where
 * that has to be a {@code Map<String,Object>} anyway (llm-gateway's cross-service request-params
 * contract every provider param already uses). */
public record SceneEnergyDirective(String text, Map<String, Object> extraTtsParams) {

    public static SceneEnergyDirective textOnly(String text) {
        return new SceneEnergyDirective(text, Map.of());
    }
}
