package com.dalai.llama.creator.service;

import com.dalai.llama.creator.ai.GeminiRateLimitGuard;
import com.dalai.llama.creator.ai.GeminiUsageMetadataParser;
import com.dalai.llama.creator.ai.GoogleGenAiClientFactory;
import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Scores a just-generated storyboard/product-frame image against a "Pinterest reference" bar -
 * the one critic in this graph that must actually SEE the image, so unlike the text-only critics
 * (CampaignAngleCriticService, ShotPlanCriticService) it goes directly to Gemini with inline image
 * bytes (same pattern as GoogleShortVideoTypeCriticService) instead of CreatorAiService.generate,
 * since the image only exists as fresh in-memory bytes at this point - it hasn't been uploaded to
 * storage yet, and uploading a frame just to critique-and-maybe-discard it would be wasteful.
 */
@Service
public class ProductFrameCriticService {

    private static final Logger log = LoggerFactory.getLogger(ProductFrameCriticService.class);
    private static final String PROMPT_TYPE = "PRODUCT_FRAME_CRITIC";
    private static final int RESPONSE_MAX_IN_MEMORY_BYTES = 24 * 1024 * 1024;

    private final CreatorProperties properties;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;

    public ProductFrameCriticService(
            CreatorProperties properties,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            CreatorAiService creatorAiService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
    }

    public record ProductFrameCriticResult(
            String status,
            double confidence,
            int compositionScore,
            int lightingRealismScore,
            int backgroundFidelityScore,
            int productFidelityScore,
            int brandContinuityScore,
            int professionalismScore,
            /** How closely the character in frame matches their assigned reference photo - 100 when no cast reference was supplied (nothing to compare against). */
            int castConsistencyScore,
            List<String> issues,
            String summary,
            double averageScore
    ) {
        public boolean isFail() {
            return "FAIL".equals(status);
        }
    }

    public ProductFrameCriticResult critique(
            byte[] imageBytes,
            String mimeType,
            String shotDescription,
            String plannedLighting,
            String plannedSetDesign,
            String castReferenceNote,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        if (imageBytes == null || imageBytes.length == 0) {
            return skipped("No image bytes were supplied to critique.");
        }
        String model = defaultString(properties.getAi().getGeminiModel(), "gemini-2.5-flash");
        String prompt = buildPrompt(shotDescription, plannedLighting, plannedSetDesign, castReferenceNote);
        try {
            Map<String, Object> request = buildRequest(prompt, imageBytes, mimeType);
            Map<String, Object> response = geminiRateLimitGuard.execute(PROMPT_TYPE, model, () ->
                    googleGenAiClientFactory.client(RESPONSE_MAX_IN_MEMORY_BYTES)
                            .post()
                            .uri(googleGenAiClientFactory.generateContentUri(model))
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                            })
                            .block(Duration.ofMillis(properties.getAi().getTimeoutMs()))
            );
            ProductFrameCriticResult result = normalize(outputText(response == null ? Map.of() : response));
            billForCall(prompt, response, model, tenantId, userId, projectId);
            return result;
        } catch (RuntimeException ex) {
            log.warn("Product frame critique failed, treating as WARN and skipping regeneration errorType={} errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return skipped("Critique failed: " + ex.getMessage());
        }
    }

    private void billForCall(String prompt, Map<String, Object> response, String model, String tenantId, String userId, UUID projectId) {
        Map<String, Object> usage = usageMetadataParser.parse(response == null ? null : response.get("usageMetadata"));
        long inputTokens = Math.max(longValue(usage.get("inputTokens")), pricingService.estimateTextTokens(prompt));
        long outputTokens = longValue(usage.get("outputTokens"));
        String usageSource = inputTokens > 0 || outputTokens > 0 ? "PROVIDER" : "ESTIMATED";
        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, inputTokens, outputTokens, usageSource);
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        creatorAiService.publishProviderUsageDebit(
                PROMPT_TYPE,
                "gemini",
                model,
                costMetadata,
                new CreatorAiService.AiUsageContext(defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), projectId, null, null),
                "Product frame quality critique"
        );
    }

    private Map<String, Object> buildRequest(String prompt, byte[] imageBytes, String mimeType) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        parts.add(Map.of("inline_data", Map.of(
                "mime_type", defaultString(mimeType, "image/jpeg"),
                "data", Base64.getEncoder().encodeToString(imageBytes)
        )));
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of("parts", List.of(Map.of("text", "Return only one valid JSON object. Do not wrap it in markdown."))));
        request.put("contents", List.of(Map.of("role", "user", "parts", parts)));
        request.put("generationConfig", generationConfig);
        return request;
    }

    private String buildPrompt(String shotDescription, String plannedLighting, String plannedSetDesign, String castReferenceNote) {
        return """
                You are a photo art director reviewing one generated advertising still against a "Pinterest reference board" bar - the kind of frame a professional creative team would actually publish, not something that reads as AI-generated.

                What this shot was supposed to be:
                Shot description: %s
                Planned lighting design: %s
                Planned set/background design: %s
                Cast reference check: %s

                Score the ATTACHED IMAGE (not the plan) on:
                - compositionScore: framing, balance, focal point - does the eye go where it should.
                - lightingRealismScore: does the lighting actually look like the planned design above (or, if no plan given, does it look like intentional, motivated lighting rather than flat/generic).
                - backgroundFidelityScore: does the set/background match what was planned and look like a real, coherent space.
                - productFidelityScore: is the product's packaging, logo, and shape rendered without distortion, warping, or invented text.
                - brandContinuityScore: does this look like it belongs in the same shoot as a professional campaign (color grade, mood, quality bar).
                - professionalismScore: the single most important score - does this look like a professionally shot commercial frame, or does it have tells of being AI-generated (extra fingers, garbled text, waxy skin, impossible geometry, inconsistent shadows).
                - castConsistencyScore: only meaningful if a cast reference check is given above - does the character in the image match that reference photo (same face, same identity). Score 100 if no cast reference was supplied.

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "compositionScore": 0,
                  "lightingRealismScore": 0,
                  "backgroundFidelityScore": 0,
                  "productFidelityScore": 0,
                  "brandContinuityScore": 0,
                  "professionalismScore": 0,
                  "castConsistencyScore": 0,
                  "issues": [],
                  "summary": ""
                }

                status is FAIL only for a real, fixable problem worth spending another generation on (visible AI artifacts, distorted product, wrong lighting/background entirely, face drift). Use WARN for usable-but-imperfect, PASS otherwise.
                """.formatted(
                        blankToNone(shotDescription), blankToNone(plannedLighting), blankToNone(plannedSetDesign), blankToNone(castReferenceNote)
                );
    }

    @SuppressWarnings("unchecked")
    private ProductFrameCriticResult normalize(String rawText) {
        Map<String, Object> output = parseJsonObject(rawText);
        String status = normalizeStatus(stringValue(output.get("status")));
        double confidence = clamp(doubleValue(output.get("confidence"), 0.5), 0.0, 1.0);
        int composition = clampInt(intValue(output.get("compositionScore")), 0, 100);
        int lighting = clampInt(intValue(output.get("lightingRealismScore")), 0, 100);
        int background = clampInt(intValue(output.get("backgroundFidelityScore")), 0, 100);
        int product = clampInt(intValue(output.get("productFidelityScore")), 0, 100);
        int brand = clampInt(intValue(output.get("brandContinuityScore")), 0, 100);
        int professionalism = clampInt(intValue(output.get("professionalismScore")), 0, 100);
        int castConsistency = clampInt(intValue(output.get("castConsistencyScore"), 100), 0, 100);
        List<String> issues = stringList(output.get("issues"));
        String summary = stringValue(output.get("summary"));
        double average = (composition + lighting + background + product + brand + professionalism + castConsistency) / 7.0;
        return new ProductFrameCriticResult(status, confidence, composition, lighting, background, product, brand, professionalism, castConsistency, issues, summary, average);
    }

    private ProductFrameCriticResult skipped(String reason) {
        return new ProductFrameCriticResult("WARN", 0.0, 0, 0, 0, 0, 0, 0, 100, List.of(reason), reason, 0.0);
    }

    private String outputText(Map<String, Object> response) {
        Object candidates = response.get("candidates");
        if (!(candidates instanceof List<?> candidateItems)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object candidateItem : candidateItems) {
            Map<String, Object> candidate = mapValue(candidateItem);
            Map<String, Object> content = mapValue(candidate.get("content"));
            if (content.get("parts") instanceof List<?> partItems) {
                for (Object partItem : partItems) {
                    Object partText = mapValue(partItem).get("text");
                    if (partText != null && !String.valueOf(partText).isBlank()) {
                        text.append(partText);
                    }
                }
            }
        }
        return text.toString();
    }

    private Map<String, Object> parseJsonObject(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return new LinkedHashMap<>();
        }
        String cleaned = outputText.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[A-Za-z0-9_-]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        try {
            return objectMapper.readValue(cleaned, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), item);
                }
            });
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = stringValue(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private String normalizeStatus(String raw) {
        String normalized = defaultString(raw, "").toUpperCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "PASS", "PASSED", "OK", "COMPLETED" -> "PASS";
            case "FAIL", "FAILED", "ERROR" -> "FAIL";
            default -> "WARN";
        };
    }

    private String blankToNone(String value) {
        return value == null || value.isBlank() ? "(none supplied)" : value;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int intValue(Object value) {
        return intValue(value, 0);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        try {
            return value == null ? 0 : Math.max(0, Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
