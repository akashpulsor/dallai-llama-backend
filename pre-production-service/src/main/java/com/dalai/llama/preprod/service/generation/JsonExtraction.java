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

    /** Same JSON-mode params PLUS Google Search grounding on Gemini 2.5+. Opt-in per call because
     * grounded queries are billed separately -- use it for audience-aware content generation
     * (script generation especially for investor / market-education / B2B briefs) where the
     * model needs real numbers, and stick with plain JSON_MODE_PARAMS for critic passes and
     * other reasoning-only calls. */
    public static final Map<String, Object> JSON_MODE_WITH_SEARCH_PARAMS =
            Map.of("response_format", "json", "google_search", true);

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
