package com.dalai.llama.critic.service.marketingplancritique;

import java.util.Map;

/** Every LLM call this package makes requests JSON mode at the API level (llm-gateway's
 * {@code response_format=json} param), so this is a safety net, not the primary defense -- strips
 * a ```json ... ``` fence if a model wraps its output in one anyway. Copy of the shot-critique
 * package's own {@code JsonExtraction}, since that one is package-private. */
final class JsonExtraction {

    private JsonExtraction() {
    }

    static final Map<String, Object> JSON_MODE_PARAMS = Map.of("response_format", "json", "temperature", 0.2);

    static String stripCodeFence(String raw) {
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
