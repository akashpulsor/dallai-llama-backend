package com.dalai.llama.chat.service.llmgateway;

import com.dalai.llama.chat.service.ChatException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** llm-gateway's embedding-typed models return the vector as a JSON array string riding through
 * the same one-string {@code LlmGatewayChatResponse.response} field every other result type uses
 * -- this is the one place that string gets turned into a real {@code double[]}. */
public final class EmbeddingParser {

    private EmbeddingParser() {
    }

    public static double[] parse(ObjectMapper objectMapper, String content) {
        if (content == null || content.isBlank()) {
            throw ChatException.upstream("llm-gateway returned no embedding content");
        }
        try {
            return objectMapper.readValue(content, double[].class);
        } catch (Exception ex) {
            throw ChatException.upstream("Could not parse embedding response: " + ex.getMessage());
        }
    }
}
