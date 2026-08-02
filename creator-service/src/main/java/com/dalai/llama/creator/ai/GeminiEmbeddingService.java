package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text embeddings for the storyboard workspace RAG index. Deliberately
 * separate from {@link GeminiCreatorAiProvider} - that class implements the
 * generic content-generation {@link CreatorAiProvider} contract, whose
 * {@code generate} method returns a parsed JSON object, not a raw vector.
 */
@Component
public class GeminiEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingService.class);

    private final CreatorProperties properties;
    private final GoogleGenAiClientFactory clientFactory;

    public GeminiEmbeddingService(CreatorProperties properties, GoogleGenAiClientFactory clientFactory) {
        this.properties = properties;
        this.clientFactory = clientFactory;
    }

    public float[] embed(String text) {
        if (clientFactory.useVertexAi()) {
            throw new IllegalStateException(
                    "Storyboard workspace embeddings are only implemented for the ai_studio Gemini backend. "
                            + "Set CREATOR_GOOGLE_GENAI_BACKEND=ai_studio, or extend GeminiEmbeddingService for Vertex's predict format."
            );
        }
        String model = properties.getAi().getGeminiEmbeddingModel();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("content", Map.of("parts", List.of(Map.of("text", safeText(text)))));
        request.put("outputDimensionality", properties.getAi().getGeminiEmbeddingDimensions());

        Map<String, Object> response = clientFactory.client(1024 * 1024)
                .post()
                .uri(clientFactory.embedContentUri(model))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block(Duration.ofMillis(properties.getAi().getTimeoutMs()));

        return extractVector(response);
    }

    @SuppressWarnings("unchecked")
    private float[] extractVector(Map<String, Object> response) {
        Map<String, Object> embedding = response == null
                ? Map.of()
                : (Map<String, Object>) response.getOrDefault("embedding", Map.of());
        List<Object> values = (List<Object>) embedding.getOrDefault("values", List.of());
        if (values.isEmpty()) {
            log.warn("Gemini embedContent returned no values; response={}", response);
            throw new IllegalStateException("Gemini embedding response did not contain any values.");
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = ((Number) values.get(i)).floatValue();
        }
        return vector;
    }

    /**
     * Formats a vector as pgvector's text literal, e.g. "[0.1,0.2,0.3]", for
     * binding through a {@code ?::vector} cast in native repository queries.
     */
    public static String toPgVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8 + 2);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private String safeText(String text) {
        String value = text == null ? "" : text.trim();
        // Gemini's embedding model caps input around 2048 tokens; this is a
        // conservative character-based guard, not an exact token count.
        int maxChars = 8000;
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }
}
