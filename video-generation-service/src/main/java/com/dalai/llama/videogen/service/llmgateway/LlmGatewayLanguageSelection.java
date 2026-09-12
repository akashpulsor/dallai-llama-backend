package com.dalai.llama.videogen.service.llmgateway;

import java.util.Locale;

/**
 * Tiny contract-payload mirror for llm-gateway's {@code LanguageSelection}. The owner of
 * validation and provider mapping remains llm-gateway; this type only preserves the HTTP contract.
 */
public record LlmGatewayLanguageSelection(String code, String script, String region) {

    /** Converts legacy persisted BCP-47 tags without exposing them to provider adapters. */
    public static LlmGatewayLanguageSelection fromBcp47String(String bcp47Tag) {
        if (bcp47Tag == null || bcp47Tag.isBlank()) {
            return null;
        }

        String[] subtags = bcp47Tag.trim().split("-");
        if (subtags.length == 0 || subtags[0].isBlank()) {
            return null;
        }

        String code = subtags[0].toLowerCase(Locale.ROOT);
        String script = null;
        String region = null;
        for (int index = 1; index < subtags.length; index++) {
            String subtag = subtags[index];
            if (script == null && subtag.matches("[A-Za-z]{4}")) {
                script = subtag.substring(0, 1).toUpperCase(Locale.ROOT) + subtag.substring(1).toLowerCase(Locale.ROOT);
            } else if (region == null && (subtag.matches("[A-Za-z]{2}") || subtag.matches("[0-9]{3}"))) {
                region = subtag.toUpperCase(Locale.ROOT);
            }
        }
        return new LlmGatewayLanguageSelection(code, script, region);
    }
}
