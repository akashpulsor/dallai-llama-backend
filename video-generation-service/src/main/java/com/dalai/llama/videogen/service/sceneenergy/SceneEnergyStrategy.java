package com.dalai.llama.videogen.service.sceneenergy;

/** How a shot's emotion/energy reaches a TTS call -- deliberately per-model, not one fixed
 * mechanism: ElevenLabs' {@code eleven_multilingual_v2} only responds to numeric
 * {@code voice_settings} (stability/style), while a future model (e.g. {@code eleven_v3}) conveys
 * the same intent as inline text tags instead. {@link SceneEnergyStrategyResolver} picks the
 * strategy whose {@link #strategyKey()} matches the resolved TTS model's own {@code
 * model_master.capabilities.emotion_delivery} value (llm-gateway data, not a hardcoded model_id
 * check here) -- so {@code BeatDubbingService} never hardcodes which mechanism applies, and a new
 * model just needs a matching capability seed + a new strategy bean, no resolver change. */
public interface SceneEnergyStrategy {

    /** Matches {@code model_master.capabilities.emotion_delivery} for whichever model this
     * strategy implements (e.g. {@code "numeric_voice_settings"}). */
    String strategyKey();

    SceneEnergyDirective apply(String emotion, String text);
}
