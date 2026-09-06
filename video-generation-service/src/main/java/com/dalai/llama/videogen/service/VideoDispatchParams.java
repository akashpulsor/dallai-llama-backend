package com.dalai.llama.videogen.service;

import java.util.List;

/** {@code referenceImageUrls}: {@code shot_prompt_reference} rows (character face / product hero,
 * already saved at prompt-build time by {@code ShotGenerationOrchestrator.saveReferences}) turned
 * into fetchable signed URLs for llm-gateway's own {@code reference_image_urls} param -- the
 * schema and the row-saving were already there, only the read-back-and-forward step at dispatch
 * time was the gap.
 *
 * <p>{@code generateAudio}: null leaves Seedance's own default (true) untouched. Set false only
 * once a caller actually has beat-matched cloned-voice audio ready to mux under the silent
 * result -- there is no such caller yet (that's the dialogue-beats work this sets the stage for),
 * so every current call site passes null. */
public record VideoDispatchParams(
        Integer durationSeconds,
        String aspectRatio,
        Boolean generateAudio,
        List<String> referenceImageUrls,
        Long seed,
        /** Wire value of VideoResolution (see that enum's javadoc), or null to leave the
         * provider's own default untouched. */
        String resolution
) {
    public VideoDispatchParams(Integer durationSeconds, String aspectRatio) {
        this(durationSeconds, aspectRatio, null, null, null, null);
    }

    public VideoDispatchParams(Integer durationSeconds, String aspectRatio, Boolean generateAudio) {
        this(durationSeconds, aspectRatio, generateAudio, null, null, null);
    }

    public VideoDispatchParams(Integer durationSeconds, String aspectRatio, Boolean generateAudio, List<String> referenceImageUrls) {
        this(durationSeconds, aspectRatio, generateAudio, referenceImageUrls, null, null);
    }
}
