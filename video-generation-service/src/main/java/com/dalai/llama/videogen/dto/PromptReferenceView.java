package com.dalai.llama.videogen.dto;

/**
 * One saved {@code shot_prompt_reference} row, signed for display. {@code kind} is the
 * {@link com.dalai.llama.videogen.domain.ReferenceKind} name (CHARACTER_FACE, STORYBOARD,
 * CHARACTER_VOICE, BACKGROUND_MUSIC, ...) and {@code audio} says which of the two the URL is, so
 * the UI can pick a thumbnail or an audio player without having to keep its own copy of the
 * kind-to-media mapping.
 */
public record PromptReferenceView(
        String kind,
        String url,
        int slotIndex,
        boolean audio
) {
}
