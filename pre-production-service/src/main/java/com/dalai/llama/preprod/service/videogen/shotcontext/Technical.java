package com.dalai.llama.preprod.service.videogen.shotcontext;

import com.dalai.llama.preprod.domain.AspectRatio;

public record Technical(
        Integer durationSeconds,
        AspectRatio aspectRatio,
        String targetProvider,
        String targetModel,
        /** ProjectConfig.preferredVoiceModel -- a model_id pin for auto-dub's clone+synthesize
         * call (llm-gateway model_master, type=voice_clone), not this shot's own video model.
         * Null uses video-generation-service's own configured default. */
        String voiceCloneModel,
        /** ProjectConfig.preferredResolution -- "480p" / "720p" wire value, or null for
         * provider default. Serialized as-is to video-generation-service where its own
         * VideoResolution.fromWireValue handles unknown/blank tolerantly. */
        String resolution,
        /** ProjectConfig.preferredTtsModel -- a model_id pin for beat-dubbing's TTS call
         * (llm-gateway model_master, type=tts), e.g. when a built-in voice speaks directly with
         * no clone step. Null uses video-generation-service's own configured default. */
        String ttsModel
) {
    /** Back-compat: existing 5-arg call sites keep working with resolution/ttsModel=null. */
    public Technical(Integer durationSeconds, AspectRatio aspectRatio, String targetProvider,
                     String targetModel, String voiceCloneModel) {
        this(durationSeconds, aspectRatio, targetProvider, targetModel, voiceCloneModel, null, null);
    }

    /** Back-compat: existing 6-arg call sites (pre-ttsModel) keep working with ttsModel=null. */
    public Technical(Integer durationSeconds, AspectRatio aspectRatio, String targetProvider,
                     String targetModel, String voiceCloneModel, String resolution) {
        this(durationSeconds, aspectRatio, targetProvider, targetModel, voiceCloneModel, resolution, null);
    }
}
