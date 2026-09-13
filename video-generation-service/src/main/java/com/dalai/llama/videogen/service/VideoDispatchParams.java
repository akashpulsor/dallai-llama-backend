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
        String resolution,
        /** The same attachments as {@link #referenceImageUrls}, each still carrying what it IS --
         * the shot's own frame, a character's face, the product, the lighting plan. The flat URL
         * list above cannot say which is which, so a provider builder had no way to put the
         * frame in the image-to-video slot and the faces in the identity slots; it could only
         * take them in order and hope. Audio attachments (a character's voice sample, the music
         * bed) are carried here too and are exactly why the tagging matters: untagged, they are
         * one more URL in a list of images. */
        List<TaggedReference> references
) {

    /** One attachment and its kind. {@code kind} is a
     * {@link com.dalai.llama.videogen.domain.ReferenceKind} name; {@code audio} says which medium
     * the URL is, so a builder never has to infer it from the kind. */
    public record TaggedReference(String kind, String url, int slotIndex, boolean audio) {}

    public VideoDispatchParams(Integer durationSeconds, String aspectRatio, Boolean generateAudio,
                               List<String> referenceImageUrls, Long seed, String resolution) {
        this(durationSeconds, aspectRatio, generateAudio, referenceImageUrls, seed, resolution, List.of());
    }
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
