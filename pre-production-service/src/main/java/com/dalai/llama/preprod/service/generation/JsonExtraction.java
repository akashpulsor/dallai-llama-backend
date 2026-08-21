package com.dalai.llama.preprod.service.generation;

import java.util.Map;

/** Every generation call in this package now requests JSON mode at the API level (see
 * llm-gateway's {@code response_format=json} param, backed by Gemini's {@code responseMimeType}),
 * so this is a safety net, not the primary defense -- strips a ```json ... ``` fence if a model
 * wraps its output in one anyway. */
public final class JsonExtraction {

    private JsonExtraction() {
    }

    public static final Map<String, Object> JSON_MODE_PARAMS = Map.of("response_format", "json");

    public static String stripCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence != -1) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }
}
