package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiCreatorAiProvider implements CreatorAiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiCreatorAiProvider.class);

    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;

    public GeminiCreatorAiProvider(
            CreatorProperties properties,
            ObjectMapper objectMapper,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public Map<String, Object> generate(String promptType, Map<String, Object> input) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", "Return only a valid JSON object. Do not wrap it in markdown."))
        ));
        request.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", promptText(input)))
        )));
        boolean useGoogleSearch = Boolean.TRUE.equals(input.get("useGoogleSearch")) || Boolean.TRUE.equals(input.get("useWebSearch"));
        if (useGoogleSearch) {
            request.put("tools", List.of(Map.of("google_search", Map.of())));
        }
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (!useGoogleSearch) {
            generationConfig.put("responseMimeType", "application/json");
        }
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        }
        request.put("generationConfig", generationConfig);

        String model = properties.getAi().getGeminiModel();
        Map<String, Object> response = geminiRateLimitGuard.execute(promptType, model, () ->
                googleGenAiClientFactory.client(16 * 1024 * 1024)
                        .post()
                        .uri(googleGenAiClientFactory.generateContentUri(model))
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                        })
                        .block(Duration.ofMillis(properties.getAi().getTimeoutMs()))
        );

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
        if (shouldRetainRawText(promptType)) {
            parsed.put("rawText", outputText);
        }

        parsed.putIfAbsent("provider", providerName());
        parsed.putIfAbsent("model", properties.getAi().getGeminiModel());
        parsed.putIfAbsent("googleGenaiBackend", googleGenAiClientFactory.backend());
        parsed.putIfAbsent("promptType", promptType);
        parsed.putIfAbsent("status", "completed");
        parsed.putIfAbsent("rawTextPreview", truncate(outputText, 4000));
        parsed.putIfAbsent("rawTextLength", outputText == null ? 0 : outputText.length());
        parsed.putIfAbsent("configuredMaxOutputTokens", properties.getAi().getMaxOutputTokens());
        parsed.putIfAbsent("timeoutMs", properties.getAi().getTimeoutMs());

        Map<String, Object> usage = mapValue(response.get("usageMetadata"));
        if (!usage.isEmpty()) {
            parsed.put("tokenUsage", usageMetadataParser.parse(usage));
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
        if (shouldLogRawText(promptType)) {
            log.info(
                    "Gemini raw response promptType={} model={} backend={} rawTextLength={} rawText={}",
                    promptType,
                    properties.getAi().getGeminiModel(),
                    googleGenAiClientFactory.backend(),
                    outputText == null ? 0 : outputText.length(),
                    outputText
            );
        }
        List<Map<String, Object>> groundingMetadata = groundingMetadata(response);
        if (!groundingMetadata.isEmpty()) {
            parsed.put("groundingMetadata", groundingMetadata);
            parsed.put("groundedWithGoogleSearch", true);
        }
        return parsed;
    }

    private boolean shouldRetainRawText(String promptType) {
        String normalized = String.valueOf(promptType == null ? "" : promptType).toUpperCase();
        return normalized.equals("SCRIPT_GENERATE")
                || normalized.equals("STORYBOARD_TAG_GENERATE")
                || normalized.equals("LIGHTING_BUILD_SHEET_TAG_GENERATE")
                || normalized.equals("CAMERA_PLAN_SHEET_TAG_GENERATE")
                || normalized.equals("SHOT_JSON_EDIT");
    }

    private boolean shouldLogRawText(String promptType) {
        String normalized = String.valueOf(promptType == null ? "" : promptType);
        return "SCRIPT_GENERATE".equalsIgnoreCase(normalized)
                || "SHOT_JSON_EDIT".equalsIgnoreCase(normalized);
    }

    private List<Map<String, Object>> groundingMetadata(Map<String, Object> response) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        Object candidates = response.get("candidates");
        if (candidates instanceof List<?> candidateItems) {
            for (Object candidateItem : candidateItems) {
                Map<String, Object> candidate = mapValue(candidateItem);
                Map<String, Object> grounding = mapValue(candidate.get("groundingMetadata"));
                if (!grounding.isEmpty()) {
                    metadata.add(grounding);
                }
            }
        }
        return metadata;
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

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
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
