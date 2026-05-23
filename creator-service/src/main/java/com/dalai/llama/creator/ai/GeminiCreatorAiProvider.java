package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiCreatorAiProvider implements CreatorAiProvider {

    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public GeminiCreatorAiProvider(
            CreatorProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public Map<String, Object> generate(String promptType, Map<String, Object> input) {
        String apiKey = properties.getAi().getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured for creator-service.");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", "Return only a valid JSON object. Do not wrap it in markdown."))
        ));
        request.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", promptText(input)))
        )));
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        }
        request.put("generationConfig", generationConfig);

        Map<String, Object> response = webClientBuilder
                .baseUrl(properties.getAi().getGeminiBaseUrl())
                .defaultHeader("x-goog-api-key", apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri("/models/{model}:generateContent", properties.getAi().getGeminiModel())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block(Duration.ofMillis(properties.getAi().getTimeoutMs()));

        return normalizeResponse(promptType, response == null ? Map.of() : response);
    }

    private String promptText(Map<String, Object> input) {
        Object renderedPrompt = input.get("renderedPrompt");
        if (renderedPrompt != null && !String.valueOf(renderedPrompt).isBlank()) {
            return String.valueOf(renderedPrompt);
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException ex) {
            return String.valueOf(input);
        }
    }

    private Map<String, Object> normalizeResponse(String promptType, Map<String, Object> response) {
        String outputText = outputText(response);
        Map<String, Object> parsed = parseJsonObject(outputText);
        if (parsed.isEmpty()) {
            parsed.put("rawText", outputText);
        }

        parsed.putIfAbsent("provider", providerName());
        parsed.putIfAbsent("model", properties.getAi().getGeminiModel());
        parsed.putIfAbsent("promptType", promptType);
        parsed.putIfAbsent("status", "completed");

        Map<String, Object> usage = mapValue(response.get("usageMetadata"));
        if (!usage.isEmpty()) {
            Map<String, Object> tokenUsage = new LinkedHashMap<>();
            tokenUsage.put("inputTokens", usage.get("promptTokenCount"));
            tokenUsage.put("outputTokens", usage.get("candidatesTokenCount"));
            tokenUsage.put("totalTokens", usage.get("totalTokenCount"));
            tokenUsage.put("raw", usage);
            parsed.put("tokenUsage", tokenUsage);
        }

        List<String> finishReasons = finishReasons(response);
        if (!finishReasons.isEmpty()) {
            parsed.put("finishReasons", finishReasons);
            parsed.put("finishReason", finishReasons.get(0));
        }

        Object promptFeedback = response.get("promptFeedback");
        if (promptFeedback != null) {
            parsed.put("promptFeedback", promptFeedback);
        }
        return parsed;
    }

    private List<String> finishReasons(Map<String, Object> response) {
        List<String> reasons = new ArrayList<>();
        Object candidates = response.get("candidates");
        if (candidates instanceof List<?> candidateItems) {
            for (Object candidateItem : candidateItems) {
                Map<String, Object> candidate = mapValue(candidateItem);
                Object reason = candidate.get("finishReason");
                if (reason != null && !String.valueOf(reason).isBlank()) {
                    reasons.add(String.valueOf(reason));
                }
            }
        }
        return reasons;
    }

    private Map<String, Object> parseJsonObject(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return new LinkedHashMap<>();
        }
        String cleaned = stripJsonFence(outputText.trim());
        try {
            return objectMapper.readValue(cleaned, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            String objectJson = firstBalancedJsonObject(cleaned);
            if (!objectJson.isBlank()) {
                try {
                    return objectMapper.readValue(objectJson, new TypeReference<LinkedHashMap<String, Object>>() {
                    });
                } catch (Exception ignored) {
                    return new LinkedHashMap<>();
                }
            }
            return new LinkedHashMap<>();
        }
    }

    private String firstBalancedJsonObject(String text) {
        int start = text == null ? -1 : text.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (character == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, index + 1).trim();
                }
            }
        }
        return "";
    }

    private String stripJsonFence(String value) {
        if (value.startsWith("```")) {
            String cleaned = value.replaceFirst("^```(?:json)?\\s*", "");
            return cleaned.replaceFirst("\\s*```$", "").trim();
        }
        return value;
    }

    private String outputText(Map<String, Object> response) {
        List<String> parts = new ArrayList<>();
        Object candidates = response.get("candidates");
        if (candidates instanceof List<?> candidateItems) {
            for (Object candidateItem : candidateItems) {
                Map<String, Object> candidate = mapValue(candidateItem);
                Map<String, Object> content = mapValue(candidate.get("content"));
                Object contentParts = content.get("parts");
                if (contentParts instanceof List<?> partItems) {
                    for (Object partItem : partItems) {
                        Map<String, Object> part = mapValue(partItem);
                        Object text = part.get("text");
                        if (text != null) {
                            parts.add(String.valueOf(text));
                        }
                    }
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
