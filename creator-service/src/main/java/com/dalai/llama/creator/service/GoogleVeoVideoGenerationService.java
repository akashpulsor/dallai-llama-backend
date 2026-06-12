package com.dalai.llama.creator.service;

import com.dalai.llama.creator.ai.GeminiRateLimitGuard;
import com.dalai.llama.creator.ai.GoogleGenAiClientFactory;
import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GoogleVeoVideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(GoogleVeoVideoGenerationService.class);
    private static final String DEFAULT_MODEL = "veo-3.1-generate-preview";
    private static final String DEFAULT_VIDEO_MIME_TYPE = "video/mp4";
    private static final int VIDEO_RESPONSE_MAX_IN_MEMORY_BYTES = 512 * 1024 * 1024;

    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;
    private final CreatorAiPricingService pricingService;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;

    public GoogleVeoVideoGenerationService(
            CreatorProperties properties,
            ObjectMapper objectMapper,
            CreatorAiPricingService pricingService,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.pricingService = pricingService;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
    }

    public GeneratedVideo generateFromImage(String prompt, byte[] referenceImageBytes, String referenceContentType, String screenType) {
        if (referenceImageBytes == null || referenceImageBytes.length == 0) {
            throw new IllegalArgumentException("A reference image is required before generating a Veo shot video.");
        }
        String model = stringValue(properties.getAi().getGeminiVideoModel(), DEFAULT_MODEL);
        Map<String, Object> request = buildRequest(prompt, referenceImageBytes, referenceContentType, screenType);
        WebClient client = googleGenAiClientFactory.client(VIDEO_RESPONSE_MAX_IN_MEMORY_BYTES);
        log.info("Google Veo shot video request model={} backend={} screenType={} prompt={}",
                model,
                googleGenAiClientFactory.backend(),
                screenType,
                prompt);

        JsonNode startResponse;
        try {
            startResponse = geminiRateLimitGuard.execute("VEO_VIDEO_START", model, () ->
                    client
                            .post()
                            .uri(googleGenAiClientFactory.predictLongRunningUri(model))
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block(Duration.ofMillis(properties.getAi().getTimeoutMs()))
            );
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("Google Veo request failed HTTP %s for model %s. body=%s"
                    .formatted(ex.getStatusCode(), model, truncate(ex.getResponseBodyAsString(), 3000)), ex);
        }

        String operationName = textAt(startResponse, "name");
        if (operationName.isBlank()) {
            throw new IllegalStateException("Google Veo did not return a long-running operation name.");
        }

        JsonNode completedOperation = waitForOperation(client, operationName);
        String videoUri = firstVideoUri(completedOperation);
        if (videoUri.isBlank()) {
            throw new IllegalStateException("Google Veo operation completed without a downloadable video URI.");
        }

        byte[] videoBytes = client
                .get()
                .uri(URI.create(videoUri))
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 180000)));

        if (videoBytes == null || videoBytes.length == 0) {
            throw new IllegalStateException("Google Veo returned an empty video payload.");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "google_veo");
        metadata.put("model", model);
        metadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());
        metadata.put("operationName", operationName);
        metadata.put("videoUri", videoUri);
        metadata.put("responsePath", "response.generateVideoResponse.generatedSamples[0].video.uri");
        metadata.put("durationSeconds", properties.getAi().getGeminiVideoDurationSeconds());
        metadata.put("resolution", properties.getAi().getGeminiVideoResolution());
        metadata.put("nativeAudioRequested", true);
        metadata.put("costMetadata", pricingService.estimateVeoCall(
                model,
                decimal(properties.getAi().getGeminiVideoDurationSeconds(), BigDecimal.valueOf(8)),
                properties.getAi().getGeminiVideoResolution(),
                true,
                "veo_video_generation"
        ));
        return new GeneratedVideo(
                videoBytes,
                DEFAULT_VIDEO_MIME_TYPE,
                metadata,
                operationName,
                request,
                objectMapper.convertValue(completedOperation, new TypeReference<>() {})
        );
    }

    private Map<String, Object> buildRequest(String prompt, byte[] referenceImageBytes, String referenceContentType, String screenType) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("imageBytes", Base64.getEncoder().encodeToString(referenceImageBytes));
        image.put("mimeType", stringValue(referenceContentType, "image/png"));

        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("prompt", stringValue(prompt, "Generate a polished creator video shot."));
        instance.put("image", image);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("aspectRatio", "horizontal".equalsIgnoreCase(screenType) ? "16:9" : "9:16");
        parameters.put("numberOfVideos", 1);
        parameters.put("durationSeconds", integerOrString(properties.getAi().getGeminiVideoDurationSeconds(), 8));
        parameters.put("resolution", stringValue(properties.getAi().getGeminiVideoResolution(), "1080p"));
        String personGeneration = stringValue(properties.getAi().getGeminiVideoPersonGeneration(), "");
        if (!personGeneration.isBlank()) {
            parameters.put("personGeneration", personGeneration);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("instances", java.util.List.of(instance));
        request.put("parameters", parameters);
        return request;
    }

    private JsonNode waitForOperation(WebClient client, String operationName) {
        long timeoutMs = Math.max(60000, properties.getAi().getGeminiVideoTimeoutMs());
        long pollIntervalMs = Math.max(1000, properties.getAi().getGeminiVideoPollIntervalMs());
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        while (Instant.now().isBefore(deadline)) {
            JsonNode operation = client
                    .get()
                    .uri(googleGenAiClientFactory.operationUri(operationName))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 60000)));

            if (operation != null && operation.path("error").isObject()) {
                throw new IllegalStateException("Google Veo operation failed: " + operation.path("error"));
            }
            if (operation != null && operation.path("done").asBoolean(false)) {
                return operation;
            }
            sleep(pollIntervalMs);
        }
        throw new IllegalStateException("Google Veo operation timed out after " + timeoutMs + " ms.");
    }

    private String firstVideoUri(JsonNode operation) {
        String uri = textAt(operation, "response", "generateVideoResponse", "generatedSamples", "0", "video", "uri");
        if (!uri.isBlank()) return uri;
        uri = textAt(operation, "response", "generatedVideos", "0", "video", "uri");
        if (!uri.isBlank()) return uri;
        uri = textAt(operation, "response", "videos", "0", "uri");
        if (!uri.isBlank()) return uri;
        return textAt(operation, "response", "video", "uri");
    }

    private String textAt(JsonNode node, String... path) {
        JsonNode current = node;
        for (String item : path) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return "";
            }
            current = item.matches("\\d+") ? current.path(Integer.parseInt(item)) : current.path(item);
        }
        return current == null || current.isMissingNode() || current.isNull() ? "" : current.asText("");
    }

    private Object integerOrString(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return value;
        }
    }

    private BigDecimal decimal(String value, BigDecimal fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String stringValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Google Veo.", ex);
        }
    }

    public record GeneratedVideo(
            byte[] bytes,
            String contentType,
            Map<String, Object> metadata,
            String operationName,
            Map<String, Object> providerRequest,
            Map<String, Object> providerResponse
    ) {
    }
}
