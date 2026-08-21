package com.dalai.llama.postprod.dto;

/** text: null/blank uses a fixed sample phrase so a preview works even before any real dialogue
 * line exists yet for this voice. */
public record PreviewVoiceRequest(
        String text,
        String model
) {
}
