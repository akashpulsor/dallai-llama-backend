package com.dalai.llama.videogen.service;

import java.util.Map;

/** Shared JSON-response handling for the llm-gateway calls in this package that expect JSON back.
 * {@link #JSON_MODE_PARAMS} is the primary defense -- it asks llm-gateway for
 * {@code response_format=json} so the model does not wrap its answer in prose or a fence in the
 * first place. {@link #stripCodeFence} is the safety net for when a model does it anyway.
 *
 * <p>Both were missing here: foley-cue and model-recommendation sent empty params and parsed the
 * raw response, so a ```json fence made Jackson fail on the leading backtick -- the call was paid
 * for and the result thrown away. Mirrors critic-service's helper of the same name. */
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
