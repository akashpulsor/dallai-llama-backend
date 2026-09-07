package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.AspectRatio;

/** Every field optional/nullable -- callers set only what they're changing, same partial-update
 * convention used elsewhere in this service (e.g. CastAssignment). */
public record UpdateProjectConfigRequest(
        AspectRatio aspectRatio,
        Integer targetDurationSeconds,
        Boolean preferMotionGraphics,
        String dialogueLanguage,
        /** A model_id from llm-gateway's real model_master (type=voice_clone) -- e.g.
         * "fal-ai/minimax/voice-clone" or "elevenlabs/instant-voice-clone". Null clears the
         * project's pin, back to video-generation-service's own configured default. */
        String preferredVoiceModel,
        /** A model_id from llm-gateway's real model_master (type=video) -- e.g. "seedance-v1"
         * or "alibaba/wan-3.0-prime/image-to-video". Null clears the pin, letting video-gen's
         * auto-recommendation pick per-shot. Wired from the creator-UI video-workspace's model
         * dropdown (VideoGenerationSection). */
        String preferredVideoModel,
        /** Project-level default video resolution -- "480p" or "720p" (matches VideoResolution
         * wireValues in video-generation-service). Null leaves current value untouched; empty
         * string clears the pin back to "provider default". No 1080p option: no configured
         * provider currently supports it (see VideoResolution.java class-level comment). */
        String preferredResolution,
        /** Feature-flag gates -- null leaves the current value untouched, matching the partial
         * -update convention every other field on this record already uses. See ProjectConfig
         * entity javadoc and migration V48 for per-flag intent. */
        Boolean recommenderEnabled,
        Boolean costPreviewEnabled,
        Boolean autoCloneAudioPromptEnabled,
        Boolean priceDeltaModalEnabled
) {
}
