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
        String voiceCloneModel
) {
}
