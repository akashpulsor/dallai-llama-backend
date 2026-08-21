package com.dalai.llama.videogen.service;

/** v1 is text-to-video only -- reference-image conditioning (turning
 * {@code shot_prompt_reference} rows into fetchable URLs for llm-gateway's
 * {@code reference_image_urls}) is a fast-follow, not built this pass. */
public record VideoDispatchParams(
        Integer durationSeconds,
        String aspectRatio
) {
}
