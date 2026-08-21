package com.dalai.llama.llmgateway.dto;

public record LanguageSummary(
        String languageCode,
        String displayName,
        String nativeName
) {
}
