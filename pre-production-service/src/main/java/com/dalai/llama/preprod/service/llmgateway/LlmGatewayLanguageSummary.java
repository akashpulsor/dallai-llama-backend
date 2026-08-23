package com.dalai.llama.preprod.service.llmgateway;

/** Mirrors llm-gateway's own {@code LanguageSummary} DTO field-for-field -- a thin JSON-shape twin
 * kept local rather than a shared library, same boundary every other llm-gateway response record
 * in this package already draws. */
public record LlmGatewayLanguageSummary(
        String languageCode,
        String displayName,
        String nativeName
) {
}
