package com.dalai.llama.creator.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GeminiUsageMetadataParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiUsageMetadataParser.class);

    private final ObjectMapper objectMapper;

    public GeminiUsageMetadataParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> parse(Object usageMetadata) {
        Map<String, Object> usage = asMap(usageMetadata);
        long inputTokens = longValue(usage.get("promptTokenCount"));
        long visibleOutputTokens = longValue(usage.get("candidatesTokenCount"));
        long thoughtsTokens = longValue(usage.get("thoughtsTokenCount"));
        long toolUsePromptTokens = longValue(usage.get("toolUsePromptTokenCount"));
        long providerTotalTokens = longValue(usage.get("totalTokenCount"));
        long billableOutputTokens = Math.max(0, visibleOutputTokens) + Math.max(0, thoughtsTokens);
        long verifiedTotal = Math.max(0, inputTokens) + Math.max(0, billableOutputTokens);
        long totalTokens = providerTotalTokens > 0 ? Math.max(providerTotalTokens, verifiedTotal) : verifiedTotal;

        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        tokenUsage.put("inputTokens", inputTokens);
        tokenUsage.put("outputTokens", billableOutputTokens);
        tokenUsage.put("visibleOutputTokens", visibleOutputTokens);
        tokenUsage.put("thoughtsTokens", thoughtsTokens);
        tokenUsage.put("toolUsePromptTokens", toolUsePromptTokens);
        tokenUsage.put("totalTokens", totalTokens);
        tokenUsage.put("providerTotalTokens", providerTotalTokens);
        tokenUsage.put("raw", usage);
        return tokenUsage;
    }

    public boolean hasUsage(Object usageMetadata) {
        return !asMap(usageMetadata).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), item);
                }
            });
            return normalized;
        }
        if (value instanceof JsonNode node) {
            return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(string);
                JsonNode usageNode = node.has("usageMetadata") ? node.path("usageMetadata") : node;
                if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
                    return Map.of();
                }
                return objectMapper.convertValue(usageNode, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (JsonProcessingException ex) {
                log.warn("Could not parse Gemini usage metadata JSON: {}", ex.getMessage());
                return Map.of();
            }
        }
        try {
            return objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IllegalArgumentException ex) {
            log.warn("Could not convert Gemini usage metadata type={} message={}", value.getClass().getName(), ex.getMessage());
            return Map.of();
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Math.max(0, Long.parseLong(string.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
