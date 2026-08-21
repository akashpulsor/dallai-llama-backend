package com.dalai.llama.creator.dto.request;

public record EmbedOfflineStoryboardHtmlRequest(
        String html,
        String watermarkText
) {
}
