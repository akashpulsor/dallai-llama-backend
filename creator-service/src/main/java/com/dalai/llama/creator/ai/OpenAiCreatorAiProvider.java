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
public class OpenAiCreatorAiProvider implements CreatorAiProvider {

    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public OpenAiCreatorAiProvider(
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
        return "openai";
    }

    @Override
    public Map<String, Object> generate(String promptType, Map<String, Object> input) {
        String apiKey = properties.getAi().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is not configured for creator-service.");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getAi().getModel());
        request.put("instructions", "Return only a valid JSON object. Do not wrap it in markdown.");
        request.put("input", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of(
                        "type", "input_text",
                        "text", promptText(input)
                ))
        )));
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            request.put("max_output_tokens", properties.getAi().getMaxOutputTokens());
        }

        Map<String, Object> response = webClientBuilder
                .baseUrl(properties.getAi().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri("/responses")
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
        parsed.putIfAbsent("model", properties.getAi().getModel());
        parsed.putIfAbsent("promptType", promptType);
        parsed.putIfAbsent("responseId", response.get("id"));
        parsed.putIfAbsent("responseStatus", response.getOrDefault("status", "completed"));
        parsed.putIfAbsent("status", response.getOrDefault("status", "completed"));
        parsed.putIfAbsent("rawTextPreview", truncate(outputText, 4000));
        parsed.putIfAbsent("rawTextLength", outputText == null ? 0 : outputText.length());
        parsed.putIfAbsent("configuredMaxOutputTokens", properties.getAi().getMaxOutputTokens());
        parsed.putIfAbsent("timeoutMs", properties.getAi().getTimeoutMs());

        List<String> finishReasons = finishReasons(response);
        if (!finishReasons.isEmpty()) {
            parsed.putIfAbsent("finishReasons", finishReasons);
            parsed.putIfAbsent("finishReason", finishReasons.get(0));
        }
        Object incompleteDetails = response.get("incomplete_details");
        if (incompleteDetails != null) {
            parsed.putIfAbsent("incompleteDetails", incompleteDetails);
        }

        Map<String, Object> usage = mapValue(response.get("usage"));
        if (!usage.isEmpty()) {
            Map<String, Object> tokenUsage = new LinkedHashMap<>();
            tokenUsage.put("inputTokens", usage.get("input_tokens"));
            tokenUsage.put("outputTokens", usage.get("output_tokens"));
            tokenUsage.put("totalTokens", usage.get("total_tokens"));
            tokenUsage.put("raw", usage);
            parsed.put("tokenUsage", tokenUsage);
        }
        return parsed;
    }

    private List<String> finishReasons(Map<String, Object> response) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> incompleteDetails = mapValue(response.get("incomplete_details"));
        addReason(reasons, incompleteDetails.get("reason"));

        Object output = response.get("output");
        if (output instanceof List<?> outputItems) {
            for (Object outputItem : outputItems) {
                Map<String, Object> item = mapValue(outputItem);
                addReason(reasons, item.get("finish_reason"));
                addReason(reasons, item.get("finishReason"));
                Object itemStatus = item.get("status");
                if (itemStatus != null && !"completed".equalsIgnoreCase(String.valueOf(itemStatus))) {
                    addReason(reasons, "output_status:" + itemStatus);
                }
            }
        }

        Object choices = response.get("choices");
        if (choices instanceof List<?> choiceItems) {
            for (Object choiceItem : choiceItems) {
                Map<String, Object> choice = mapValue(choiceItem);
                addReason(reasons, choice.get("finish_reason"));
                addReason(reasons, choice.get("finishReason"));
            }
        }

        Object responseStatus = response.get("status");
        if (reasons.isEmpty() && responseStatus != null && !"completed".equalsIgnoreCase(String.valueOf(responseStatus))) {
            addReason(reasons, "response_status:" + responseStatus);
        }
        return reasons.stream().distinct().toList();
    }

    private void addReason(List<String> reasons, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            reasons.add(String.valueOf(value));
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
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
            return new LinkedHashMap<>();
        }
    }

    private String stripJsonFence(String value) {
        if (value.startsWith("```")) {
            String cleaned = value.replaceFirst("^```(?:json)?\\s*", "");
            return cleaned.replaceFirst("\\s*```$", "").trim();
        }
        return value;
    }

    private String outputText(Map<String, Object> response) {
        Object helperText = response.get("output_text");
        if (helperText != null && !String.valueOf(helperText).isBlank()) {
            return String.valueOf(helperText);
        }

        List<String> parts = new ArrayList<>();
        Object output = response.get("output");
        if (output instanceof List<?> outputItems) {
            for (Object outputItem : outputItems) {
                Map<String, Object> item = mapValue(outputItem);
                Object content = item.get("content");
                if (content instanceof List<?> contentItems) {
                    for (Object contentItem : contentItems) {
                        Map<String, Object> contentMap = mapValue(contentItem);
                        Object text = contentMap.get("text");
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
