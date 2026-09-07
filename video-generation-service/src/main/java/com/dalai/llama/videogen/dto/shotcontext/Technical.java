package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.AspectRatio;
import com.dalai.llama.videogen.domain.VideoResolution;
import jakarta.validation.constraints.Positive;

public record Technical(
        @Positive Integer durationSeconds,
        AspectRatio aspectRatio,
        /** Null uses the provider's own default (Seedance: 720p, Wan: 480p) -- see
         * {@link VideoResolution}'s javadoc for why only these two tiers are offered. */
        VideoResolution resolution,
        /** Informational only -- llm-gateway resolves provider from model_master once a model is
         * chosen; not required for dispatch. */
        String targetProvider,
        /** Optional pin -- when set, {@code ModelRecommendationService} is skipped entirely
         * (doc §21.6, "Override & pinning"). */
        String targetModel,
        /** ProjectConfig.preferredVoiceModel on pre-production-service's side -- a model_id pin
         * for {@code BeatDubbingService}'s clone+synthesize call, not this shot's own video
         * model. Null uses that service's own configured default. */
        String voiceCloneModel,
        /** The shot's edit-plan text (pre-production's editing notes) -- fed into the composed
         * prompt so the video model knows the intended cut/transition style. Nullable. */
        String editingNotes,
        /** ProjectConfig.preferredTtsModel on pre-production-service's side -- a model_id pin
         * (llm-gateway model_master, type=tts) for {@code BeatDubbingService}'s TTS call. Null
         * uses this service's own configured default. */
        String ttsModel
) {
    /** Back-compat: existing 6-arg call sites (pre-ttsModel) keep working with ttsModel=null. */
    public Technical(Integer durationSeconds, AspectRatio aspectRatio, VideoResolution resolution,
                     String targetProvider, String targetModel, String voiceCloneModel, String editingNotes) {
        this(durationSeconds, aspectRatio, resolution, targetProvider, targetModel, voiceCloneModel, editingNotes, null);
    }
}
