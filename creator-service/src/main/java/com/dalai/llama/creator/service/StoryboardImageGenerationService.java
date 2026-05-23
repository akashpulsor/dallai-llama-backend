package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StoryboardImageGenerationService {

    private final CreatorProperties properties;
    private final WebClient.Builder webClientBuilder;

    public StoryboardImageGenerationService(CreatorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public GeneratedImage generateStoryboardImage(String prompt, String screenType) {
        String apiKey = properties.getAi().getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured for storyboard image generation.");
        }

        String model = properties.getAi().getGeminiImageModel();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("instances", List.of(Map.of("prompt", prompt)));
        request.put("parameters", Map.of(
                "sampleCount", 1,
                "aspectRatio", "horizontal".equalsIgnoreCase(screenType) ? "16:9" : "9:16",
                "personGeneration", "allow_adult"
        ));

        Map<String, Object> response = webClientBuilder
                .baseUrl(properties.getAi().getGeminiBaseUrl())
                .defaultHeader("x-goog-api-key", apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri("/models/{model}:predict", model)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block(Duration.ofMillis(properties.getAi().getTimeoutMs()));

        return extractImage(response == null ? Map.of() : response, model);
    }

    @SuppressWarnings("unchecked")
    private GeneratedImage extractImage(Map<String, Object> response, String model) {
        Object predictionsValue = response.get("predictions");
        if (!(predictionsValue instanceof List<?> predictions) || predictions.isEmpty()) {
            throw new IllegalStateException("Gemini image provider returned no predictions.");
        }

        Object first = predictions.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            throw new IllegalStateException("Gemini image provider returned an invalid image prediction.");
        }
        Map<String, Object> prediction = (Map<String, Object>) firstMap;
        Object imageValue = firstNonNull(
                prediction.get("bytesBase64Encoded"),
                prediction.get("imageBytes"),
                nested(prediction, "image", "imageBytes"),
                nested(prediction, "image", "bytesBase64Encoded")
        );
        if (imageValue == null || String.valueOf(imageValue).isBlank()) {
            throw new IllegalStateException("Gemini image provider did not return image bytes.");
        }

        String contentType = stringValue(firstNonNull(
                prediction.get("mimeType"),
                prediction.get("mime_type"),
                nested(prediction, "image", "mimeType")
        ), "image/png");

        byte[] bytes = Base64.getDecoder().decode(String.valueOf(imageValue));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "gemini");
        metadata.put("model", model);
        metadata.put("rawContentType", contentType);
        metadata.put("predictionCount", predictions.size());
        return new GeneratedImage(bytes, contentType, metadata);
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> source, String parentKey, String childKey) {
        Object parent = source.get(parentKey);
        if (parent instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).get(childKey);
        }
        return null;
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    public record GeneratedImage(
            byte[] bytes,
            String contentType,
            Map<String, Object> metadata
    ) {
    }
}
