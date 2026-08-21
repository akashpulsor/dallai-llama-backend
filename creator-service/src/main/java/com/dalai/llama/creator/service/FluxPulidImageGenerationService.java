package com.dalai.llama.creator.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Path A of the CAST-reference identity-preserving production frame: a single fal.ai
 * flux-pulid call generates the whole scene (from a text prompt) and the face together,
 * conditioned on a cast reference photo - one cohesive generation, no compositing seam.
 * Exists because Gemini hard-blocks (finishReason=IMAGE_OTHER, zero output tokens) when a
 * real, identifiable person's photo is attached as a reference for a new photorealistic
 * generation "of" them - confirmed this cannot be worked around with prompt wording.
 *
 * Modeled directly on {@link CreatorPatchEditService}'s fal.ai queue-call mechanics
 * (submit -> poll status -> fetch result) and the billing pattern used by
 * {@link FounderAvatarPreviewService} - {@code assertWalletBalanceForModelRun} before,
 * {@code publishProviderUsageDebit} after, with provider="fal.ai" and a model starting
 * "fal-ai/" so {@link FalProviderBillingService} resolves real provider-reported cost.
 *
 * Responses are deserialized into the typed records below rather than walked as raw
 * {@code JsonNode} - {@code JsonNode.path(String)} silently misses array elements (a numeric
 * segment like "0" never resolves against an ArrayNode), which is exactly the kind of bug a
 * DTO makes impossible: Jackson maps {@code images[0].url} to a real {@code List<Image>} and
 * unrecognized fields (timings, seed, prompt echo, ...) are ignored automatically.
 */
@Service
public class FluxPulidImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FluxPulidImageGenerationService.class);
    private static final String PROMPT_TYPE = "FLUX_PULID_IDENTITY_IMAGE";
    private static final long MAX_REFERENCE_IMAGE_BYTES = 15L * 1024L * 1024L;

    private final AssetStorageService assetStorageService;
    private final CreatorAiService creatorAiService;
    private final WebClient webClient;
    private final String falKey;
    private final String falBaseUrl;
    private final String modelEndpoint;
    private final long pollIntervalMs;
    private final long timeoutMs;

    public FluxPulidImageGenerationService(
            AssetStorageService assetStorageService,
            CreatorAiService creatorAiService,
            WebClient.Builder webClientBuilder,
            @Value("${FAL_KEY:${CREATOR_FAL_KEY:}}") String falKey,
            @Value("${FLUX_PULID_FAL_BASE_URL:https://queue.fal.run}") String falBaseUrl,
            @Value("${FLUX_PULID_FAL_MODEL:fal-ai/flux-pulid}") String modelEndpoint,
            @Value("${FLUX_PULID_POLL_INTERVAL_MS:3000}") long pollIntervalMs,
            @Value("${FLUX_PULID_TIMEOUT_MS:180000}") long timeoutMs
    ) {
        this.assetStorageService = assetStorageService;
        this.creatorAiService = creatorAiService;
        this.webClient = webClientBuilder.clone()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.falKey = falKey;
        this.falBaseUrl = trimTrailingSlash(falBaseUrl);
        this.modelEndpoint = modelEndpoint;
        this.pollIntervalMs = Math.max(1000, pollIntervalMs);
        this.timeoutMs = Math.max(30000, timeoutMs);
    }

    public record GeneratedImage(byte[] bytes, Map<String, Object> metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueueSubmission(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("status_url") String statusUrl,
            @JsonProperty("response_url") String responseUrl
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueueStatus(String status, String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenerationResult(List<ResultImage> images) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResultImage(String url) {
    }

    public GeneratedImage generateIdentityPreservingFrame(
            String prompt,
            int width,
            int height,
            String castReferenceBucket,
            String castReferenceObjectKey,
            CreatorAiService.AiUsageContext usageContext
    ) {
        if (falKey.isBlank()) {
            throw new IllegalStateException("FAL_KEY is not configured - cannot reach fal.ai flux-pulid.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("A scene prompt is required for identity-preserving generation.");
        }
        if (castReferenceBucket == null || castReferenceBucket.isBlank() || castReferenceObjectKey == null || castReferenceObjectKey.isBlank()) {
            throw new IllegalStateException("A cast reference image is required for identity-preserving generation.");
        }
        creatorAiService.assertWalletBalanceForModelRun(PROMPT_TYPE, usageContext);

        String referenceDataUri = referenceImageDataUri(castReferenceBucket, castReferenceObjectKey);

        // First live test came back a default-sized (1024x768 landscape) close-up portrait
        // despite the shot plan asking for a vertical full-body Wide Shot - flux-pulid was never
        // told the target canvas (only prompt + reference_image_url were sent), and a squarish
        // default canvas biases the model toward a headshot crop regardless of prompt wording.
        // image_size pins the actual output canvas to the shot's real aspect ratio; the leading
        // directive below is a second, redundant push in the same direction since a strong early
        // instruction carries more weight than one buried inside a long scene-description prompt.
        String framedPrompt = ("Full-body, head-to-toe wide shot framed for a %dx%d vertical/portrait canvas. "
                + "The subject's entire body and complete outfit, including footwear, must be visible in frame - "
                + "do not crop to a close-up portrait, headshot, or bust shot. %s").formatted(width, height, prompt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", framedPrompt);
        body.put("reference_image_url", referenceDataUri);
        body.put("image_size", Map.of("width", width, "height", height));
        body.put("negative_prompt", "close-up portrait, headshot, bust shot, cropped body, product not fully visible, missing legs, missing footwear");

        String submitUrl = falBaseUrl + "/" + modelEndpoint;
        log.info("flux-pulid identity-preserving generation request model={} promptChars={} size={}x{}", modelEndpoint, framedPrompt.length(), width, height);
        QueueSubmission started = post(submitUrl, body, "flux-pulid submit", QueueSubmission.class);
        String requestId = defaultString(started.requestId(), "");
        if (requestId.isBlank()) {
            throw new IllegalStateException("fal.ai did not return a request id.");
        }
        String statusUrl = defaultString(started.statusUrl(), submitUrl + "/requests/" + requestId + "/status");
        String responseUrl = defaultString(started.responseUrl(), submitUrl + "/requests/" + requestId);

        GenerationResult result = pollUntilComplete(statusUrl, responseUrl, requestId);
        String imageUrl = result.images() == null || result.images().isEmpty() ? "" : defaultString(result.images().get(0).url(), "");
        if (imageUrl.isBlank()) {
            throw new IllegalStateException("flux-pulid completed without a result image URL.");
        }
        byte[] resultBytes = downloadBytes(imageUrl);

        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("provider", "fal.ai");
        costMetadata.put("model", modelEndpoint);
        costMetadata.put("falRequestId", requestId);
        creatorAiService.publishProviderUsageDebit(
                PROMPT_TYPE, "fal.ai", modelEndpoint, costMetadata, usageContext,
                "Generated identity-preserving product frame (flux-pulid)"
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "fal.ai");
        metadata.put("model", modelEndpoint);
        metadata.put("falRequestId", requestId);
        metadata.put("identityPreservingGenerationPath", "FLUX_PULID");
        log.info("flux-pulid identity-preserving generation completed requestId={} resultBytes={}", requestId, resultBytes.length);
        return new GeneratedImage(resultBytes, metadata);
    }

    private String referenceImageDataUri(String bucket, String objectKey) {
        try (AssetStorageService.StreamedObject stored = assetStorageService.openObjectStream(bucket, objectKey)) {
            if (stored.sizeBytes() > MAX_REFERENCE_IMAGE_BYTES) {
                throw new IllegalStateException("Cast reference image is too large.");
            }
            byte[] bytes = stored.inputStream().readNBytes((int) MAX_REFERENCE_IMAGE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_REFERENCE_IMAGE_BYTES) {
                throw new IllegalStateException("Cast reference image could not be read.");
            }
            String contentType = defaultString(stored.contentType(), "image/jpeg");
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load cast reference image " + bucket + "/" + objectKey + ".", ex);
        }
    }

    private GenerationResult pollUntilComplete(String statusUrl, String responseUrl, String requestId) {
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        String lastStatus = "";
        while (Instant.now().isBefore(deadline)) {
            QueueStatus status = get(statusUrl, "flux-pulid status", QueueStatus.class);
            String state = defaultString(status.status(), "").toUpperCase(Locale.ROOT);
            if (!state.equals(lastStatus)) {
                log.info("flux-pulid generation status requestId={} status={}", requestId, state);
                lastStatus = state;
            }
            if (state.equals("COMPLETED") || state.equals("SUCCEEDED") || state.equals("SUCCESS")) {
                return get(responseUrl, "flux-pulid result", GenerationResult.class);
            }
            if (state.equals("FAILED") || state.equals("ERROR") || state.equals("CANCELED") || state.equals("CANCELLED")) {
                throw new IllegalStateException("flux-pulid generation failed status=" + state + " error=" + defaultString(status.error(), ""));
            }
            sleep(pollIntervalMs);
        }
        throw new IllegalStateException("flux-pulid generation timed out after " + timeoutMs + " ms.");
    }

    private <T> T post(String url, Map<String, Object> body, String label, Class<T> responseType) {
        try {
            return webClient.post()
                    .uri(url)
                    .header("Authorization", "Key " + falKey)
                    .header("Accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(responseType)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s failed HTTP %s. body=%s"
                    .formatted(label, ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 2000)), ex);
        }
    }

    private <T> T get(String url, String label, Class<T> responseType) {
        try {
            return webClient.get()
                    .uri(url)
                    .header("Authorization", "Key " + falKey)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(responseType)
                    .block(Duration.ofMillis(Math.max(30000, pollIntervalMs * 4)));
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s failed HTTP %s. body=%s"
                    .formatted(label, ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 2000)), ex);
        }
    }

    private byte[] downloadBytes(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofMillis(Math.max(60000, timeoutMs)));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for flux-pulid generation.", ex);
        }
    }

    private String truncate(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String trimTrailingSlash(String value) {
        String text = defaultString(value, "");
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
