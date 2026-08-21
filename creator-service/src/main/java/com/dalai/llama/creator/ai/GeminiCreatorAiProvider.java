package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.service.AssetStorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class GeminiCreatorAiProvider implements CreatorAiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiCreatorAiProvider.class);
    private static final int MAX_REFERENCE_IMAGES = 8;
    private static final int MAX_REFERENCE_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOTAL_REFERENCE_BYTES = 9 * 1024 * 1024;
    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Duration REFERENCE_DOWNLOAD_TIMEOUT = Duration.ofSeconds(12);

    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final WebClient referenceImageClient;
    private AssetStorageService assetStorageService;

    public GeminiCreatorAiProvider(
            CreatorProperties properties,
            ObjectMapper objectMapper,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.referenceImageClient = webClientBuilder.clone()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_REFERENCE_IMAGE_BYTES))
                .build();
    }

    @Autowired
    void setAssetStorageService(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public Map<String, Object> generate(String promptType, Map<String, Object> input) {
        PromptParts promptParts = promptParts(input);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", "Return only a valid JSON object. Do not wrap it in markdown."))
        ));
        request.put("contents", List.of(Map.of(
                "role", "user",
                "parts", promptParts.parts()
        )));
        boolean useGoogleSearch = Boolean.TRUE.equals(input.get("useGoogleSearch")) || Boolean.TRUE.equals(input.get("useWebSearch"));
        if (useGoogleSearch) {
            request.put("tools", List.of(Map.of("google_search", Map.of())));
        }
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (!useGoogleSearch) {
            generationConfig.put("responseMimeType", "application/json");
        }
        Integer maxOutputTokensOverride = positiveIntOrNull(input.get("maxOutputTokensOverride"));
        int effectiveMaxOutputTokens = maxOutputTokensOverride != null
                ? maxOutputTokensOverride
                : (properties.getAi().getMaxOutputTokens() == null ? 0 : properties.getAi().getMaxOutputTokens());
        if (effectiveMaxOutputTokens > 0) {
            generationConfig.put("maxOutputTokens", effectiveMaxOutputTokens);
        }
        if (Boolean.TRUE.equals(input.get("disableThinking"))) {
            // Extended thinking draws from the same output-token budget as the answer itself.
            // For mechanical, non-creative tasks (e.g. lossless text compression) that budget
            // should go entirely to the answer, not reasoning the model doesn't need here.
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
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

        return normalizeResponse(promptType, response == null ? Map.of() : response, promptParts.referenceImageCount());
    }

    private PromptParts promptParts(Map<String, Object> input) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", promptText(input)));
        if (!Boolean.TRUE.equals(input.get("attachReferenceImages"))) {
            return new PromptParts(parts, 0);
        }

        int totalBytes = 0;
        int attachedCount = 0;
        Set<String> assetUrls = new LinkedHashSet<>();
        for (Map<String, Object> asset : referenceImageAssets(input)) {
            if (attachedCount >= MAX_REFERENCE_IMAGES || totalBytes >= MAX_TOTAL_REFERENCE_BYTES) {
                break;
            }
            ReferenceImage referenceImage = downloadReferenceImageAsset(asset);
            if (referenceImage == null || totalBytes + referenceImage.bytes().length > MAX_TOTAL_REFERENCE_BYTES) {
                continue;
            }
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", referenceImage.contentType(),
                    "data", Base64.getEncoder().encodeToString(referenceImage.bytes())
            )));
            totalBytes += referenceImage.bytes().length;
            attachedCount++;
            addReferenceImageUrls(assetUrls, asset.get("signedUrl"));
            addReferenceImageUrls(assetUrls, asset.get("publicUrl"));
            addReferenceImageUrls(assetUrls, asset.get("assetUrl"));
            addReferenceImageUrls(assetUrls, asset.get("url"));
        }
        for (String imageUrl : referenceImageUrls(input)) {
            if (attachedCount >= MAX_REFERENCE_IMAGES || totalBytes >= MAX_TOTAL_REFERENCE_BYTES) {
                break;
            }
            if (assetUrls.contains(imageUrl)) continue;
            ReferenceImage referenceImage = downloadReferenceImage(imageUrl);
            if (referenceImage == null || totalBytes + referenceImage.bytes().length > MAX_TOTAL_REFERENCE_BYTES) {
                continue;
            }
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", referenceImage.contentType(),
                    "data", Base64.getEncoder().encodeToString(referenceImage.bytes())
            )));
            totalBytes += referenceImage.bytes().length;
            attachedCount++;
        }
        if (attachedCount == 0) {
            log.warn("Gemini request asked for reference images, but none could be attached.");
        }
        return new PromptParts(parts, attachedCount);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> referenceImageAssets(Map<String, Object> input) {
        Object value = input.get("referenceImageAssets");
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<Map<String, Object>> assets = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> asset = new LinkedHashMap<>();
            map.forEach((key, fieldValue) -> asset.put(String.valueOf(key), fieldValue));
            if (!String.valueOf(asset.getOrDefault("bucket", "")).isBlank()
                    && !String.valueOf(asset.getOrDefault("objectKey", "")).isBlank()) {
                assets.add(asset);
            }
            if (assets.size() >= MAX_REFERENCE_IMAGES) break;
        }
        return assets;
    }

    private List<String> referenceImageUrls(Map<String, Object> input) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        addReferenceImageUrls(urls, input.get("referenceImageUrls"));
        addReferenceImageUrls(urls, input.get("productReferenceImageUrls"));
        addReferenceImageUrls(urls, input.get("productImageUrls"));
        return urls.stream().limit(MAX_REFERENCE_IMAGES).toList();
    }

    private void addReferenceImageUrls(Set<String> urls, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addReferenceImageUrls(urls, item));
            return;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(text);
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                urls.add(uri.toString());
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid references are skipped; the text prompt remains usable.
        }
    }

    private ReferenceImage downloadReferenceImageAsset(Map<String, Object> asset) {
        if (assetStorageService == null || asset == null) return null;
        String bucket = String.valueOf(asset.getOrDefault("bucket", "")).trim();
        String objectKey = String.valueOf(asset.getOrDefault("objectKey", "")).trim();
        if (bucket.isBlank() || objectKey.isBlank()
                || !bucket.equals(assetStorageService.creatorAssetsBucket())) {
            return null;
        }
        try (AssetStorageService.StreamedObject stored = assetStorageService.openObjectStream(bucket, objectKey)) {
            if (stored.sizeBytes() > MAX_REFERENCE_IMAGE_BYTES) return null;
            byte[] bytes = stored.inputStream().readNBytes(MAX_REFERENCE_IMAGE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_REFERENCE_IMAGE_BYTES) return null;
            String contentType = normalizedImageContentType(
                    String.valueOf(asset.getOrDefault("contentType", stored.contentType()))
            );
            if (!SUPPORTED_IMAGE_TYPES.contains(contentType)) return null;
            return new ReferenceImage(bytes, contentType);
        } catch (IOException | RuntimeException ex) {
            log.warn(
                    "Gemini managed reference download skipped objectKeySuffix={} errorType={}",
                    safeObjectKeySuffix(objectKey),
                    ex.getClass().getSimpleName()
            );
            return null;
        }
    }

    private String safeObjectKeySuffix(String objectKey) {
        int slash = objectKey == null ? -1 : objectKey.lastIndexOf('/');
        return slash >= 0 ? objectKey.substring(slash + 1) : String.valueOf(objectKey);
    }

    private ReferenceImage downloadReferenceImage(String imageUrl) {
        try {
            URI uri = URI.create(imageUrl);
            ResponseEntity<byte[]> response = referenceImageClient
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.ACCEPT, "image/jpeg,image/png,image/webp")
                    .retrieve()
                    .toEntity(byte[].class)
                    .block(REFERENCE_DOWNLOAD_TIMEOUT);
            byte[] bytes = response == null ? null : response.getBody();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_REFERENCE_IMAGE_BYTES) {
                return null;
            }
            String contentType = normalizedImageContentType(response.getHeaders().getContentType() == null
                    ? imageContentTypeFromPath(uri.getPath())
                    : response.getHeaders().getContentType().toString());
            if (!SUPPORTED_IMAGE_TYPES.contains(contentType)) {
                return null;
            }
            return new ReferenceImage(bytes, contentType);
        } catch (RuntimeException ex) {
            String host = "";
            try {
                host = String.valueOf(URI.create(imageUrl).getHost());
            } catch (RuntimeException ignored) {
                // Keep signed URLs and query parameters out of logs.
            }
            log.warn(
                    "Gemini product reference download skipped host={} errorType={} errorMessage={}",
                    host,
                    ex.getClass().getSimpleName(),
                    ex.getMessage()
            );
            return null;
        }
    }

    private String normalizedImageContentType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        if (separator >= 0) normalized = normalized.substring(0, separator).trim();
        if ("image/jpg".equals(normalized)) return "image/jpeg";
        return normalized;
    }

    private String imageContentTypeFromPath(String path) {
        String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".png")) {
            return "image/png";
        }
        if (normalized.endsWith(".webp")) {
            return "image/webp";
        }
        return normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") ? "image/jpeg" : "";
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

    private Map<String, Object> normalizeResponse(String promptType, Map<String, Object> response, int referenceImageCount) {
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
        parsed.putIfAbsent("attachedReferenceImageCount", referenceImageCount);

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

    private record PromptParts(List<Map<String, Object>> parts, int referenceImageCount) {
    }

    private record ReferenceImage(byte[] bytes, String contentType) {
    }

    private boolean shouldRetainRawText(String promptType) {
        String normalized = String.valueOf(promptType == null ? "" : promptType).toUpperCase();
        return normalized.equals("SCRIPT_GENERATE")
                || normalized.equals("STORYBOARD_TAG_GENERATE")
                || normalized.equals("LIGHTING_BUILD_SHEET_TAG_GENERATE")
                || normalized.equals("CAMERA_PLAN_SHEET_TAG_GENERATE")
                || normalized.equals("PRODUCTION_PLAN_TAGS_COMBINED")
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

    private Integer positiveIntOrNull(Object value) {
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return null;
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
