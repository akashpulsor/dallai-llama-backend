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
 * Path B of the CAST-reference identity-preserving production frame: stage 1 renders the
 * scene exactly as today (no reference attached, so no Gemini IMAGE_OTHER block - this is the
 * same generic-face frame already proven to match the shot's plan/composition/ad-copy), stage
 * 2 sends that frame plus the cast reference photo to a fal.ai face-swap model to blend the
 * real face in. Stage 1 is not implemented here - {@link StoryboardService} reuses its own
 * existing (already-working) Gemini generation path for that, since it is literally the same
 * "reference blocked, fall back to text-only" frame this codebase already produces today.
 *
 * Model/endpoint: {@code fal-ai/face-swap} (first-party fal.ai model - the earlier
 * {@code half-moon-ai/ai-face-swap/faceswapimage} guess 404'd as a nonexistent Application when
 * tested live). Request field names ({@code base_image_url} = target/base scene,
 * {@code swap_image_url} = source face) confirmed live by submitting an empty body and reading
 * fal.ai's own pydantic "Field required" validation error - the exact-schema uncertainty flagged
 * during planning is resolved. The result shape (a single "image" object vs. an "images" list)
 * remains a best-effort guess handled by {@link GenerationResult}; {@code post}/{@code get}'s
 * error path still logs fal.ai's actual response body on any further 4xx for the same reason.
 */
@Service
public class FaceSwapImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FaceSwapImageGenerationService.class);
    private static final String PROMPT_TYPE = "FACE_SWAP_IDENTITY_IMAGE";
    private static final long MAX_IMAGE_BYTES = 15L * 1024L * 1024L;

    private final AssetStorageService assetStorageService;
    private final CreatorAiService creatorAiService;
    private final WebClient webClient;
    private final String falKey;
    private final String falBaseUrl;
    private final String modelEndpoint;
    private final long pollIntervalMs;
    private final long timeoutMs;

    public FaceSwapImageGenerationService(
            AssetStorageService assetStorageService,
            CreatorAiService creatorAiService,
            WebClient.Builder webClientBuilder,
            @Value("${FAL_KEY:${CREATOR_FAL_KEY:}}") String falKey,
            @Value("${FACE_SWAP_FAL_BASE_URL:https://queue.fal.run}") String falBaseUrl,
            @Value("${FACE_SWAP_FAL_MODEL:fal-ai/face-swap}") String modelEndpoint,
            @Value("${FACE_SWAP_POLL_INTERVAL_MS:3000}") long pollIntervalMs,
            @Value("${FACE_SWAP_TIMEOUT_MS:180000}") long timeoutMs
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

    // "image" (singular) covers a single-output model; "images" covers a list-output one - the
    // real shape was not confirmed pre-test (see class javadoc), so both are accepted and the
    // first present wins, rather than guessing one and hard-failing on the other.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenerationResult(ResultImage image, List<ResultImage> images) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResultImage(String url) {
    }

    public GeneratedImage generateFaceSwapFrame(
            byte[] stage1SceneBytes,
            String stage1SceneContentType,
            String castReferenceBucket,
            String castReferenceObjectKey,
            CreatorAiService.AiUsageContext usageContext
    ) {
        if (falKey.isBlank()) {
            throw new IllegalStateException("FAL_KEY is not configured - cannot reach fal.ai face-swap.");
        }
        if (stage1SceneBytes == null || stage1SceneBytes.length == 0) {
            throw new IllegalStateException("A stage-1 scene image is required for face-swap.");
        }
        if (castReferenceBucket == null || castReferenceBucket.isBlank() || castReferenceObjectKey == null || castReferenceObjectKey.isBlank()) {
            throw new IllegalStateException("A cast reference image is required for face-swap.");
        }
        creatorAiService.assertWalletBalanceForModelRun(PROMPT_TYPE, usageContext);

        String sourceFaceDataUri = referenceImageDataUri(castReferenceBucket, castReferenceObjectKey);
        String targetImageDataUri = dataUri(stage1SceneContentType, stage1SceneBytes);

        // Confirmed live against fal.ai's own pydantic validation error (submitting {} to
        // fal-ai/face-swap returned "Field required" for exactly these two names) - base_image_url
        // is the target/base scene, swap_image_url is the source face to blend in.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("base_image_url", targetImageDataUri);
        body.put("swap_image_url", sourceFaceDataUri);

        String submitUrl = falBaseUrl + "/" + modelEndpoint;
        log.info("face-swap identity-preserving generation request model={} sceneBytes={}", modelEndpoint, stage1SceneBytes.length);
        QueueSubmission started = post(submitUrl, body, "face-swap submit", QueueSubmission.class);
        String requestId = defaultString(started.requestId(), "");
        if (requestId.isBlank()) {
            throw new IllegalStateException("fal.ai did not return a request id.");
        }
        String statusUrl = defaultString(started.statusUrl(), submitUrl + "/requests/" + requestId + "/status");
        String responseUrl = defaultString(started.responseUrl(), submitUrl + "/requests/" + requestId);

        GenerationResult result = pollUntilComplete(statusUrl, responseUrl, requestId);
        String imageUrl = resultImageUrl(result);
        if (imageUrl.isBlank()) {
            throw new IllegalStateException("face-swap completed without a result image URL.");
        }
        byte[] resultBytes = downloadBytes(imageUrl);

        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("provider", "fal.ai");
        costMetadata.put("model", modelEndpoint);
        costMetadata.put("falRequestId", requestId);
        creatorAiService.publishProviderUsageDebit(
                PROMPT_TYPE, "fal.ai", modelEndpoint, costMetadata, usageContext,
                "Face-swapped identity-preserving product frame"
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "fal.ai");
        metadata.put("model", modelEndpoint);
        metadata.put("falRequestId", requestId);
        metadata.put("identityPreservingGenerationPath", "FACE_SWAP");
        log.info("face-swap identity-preserving generation completed requestId={} resultBytes={}", requestId, resultBytes.length);
        return new GeneratedImage(resultBytes, metadata);
    }

    private String resultImageUrl(GenerationResult result) {
        if (result.image() != null && !defaultString(result.image().url(), "").isBlank()) {
            return result.image().url();
        }
        if (result.images() != null && !result.images().isEmpty()) {
            return defaultString(result.images().get(0).url(), "");
        }
        return "";
    }

    private String referenceImageDataUri(String bucket, String objectKey) {
        try (AssetStorageService.StreamedObject stored = assetStorageService.openObjectStream(bucket, objectKey)) {
            if (stored.sizeBytes() > MAX_IMAGE_BYTES) {
                throw new IllegalStateException("Cast reference image is too large.");
            }
            byte[] bytes = stored.inputStream().readNBytes((int) MAX_IMAGE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                throw new IllegalStateException("Cast reference image could not be read.");
            }
            return dataUri(defaultString(stored.contentType(), "image/jpeg"), bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load cast reference image " + bucket + "/" + objectKey + ".", ex);
        }
    }

    private String dataUri(String contentType, byte[] bytes) {
        return "data:" + defaultString(contentType, "image/jpeg") + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private GenerationResult pollUntilComplete(String statusUrl, String responseUrl, String requestId) {
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        String lastStatus = "";
        while (Instant.now().isBefore(deadline)) {
            QueueStatus status = get(statusUrl, "face-swap status", QueueStatus.class);
            String state = defaultString(status.status(), "").toUpperCase(Locale.ROOT);
            if (!state.equals(lastStatus)) {
                log.info("face-swap generation status requestId={} status={}", requestId, state);
                lastStatus = state;
            }
            if (state.equals("COMPLETED") || state.equals("SUCCEEDED") || state.equals("SUCCESS")) {
                return get(responseUrl, "face-swap result", GenerationResult.class);
            }
            if (state.equals("FAILED") || state.equals("ERROR") || state.equals("CANCELED") || state.equals("CANCELLED")) {
                throw new IllegalStateException("face-swap generation failed status=" + state + " error=" + defaultString(status.error(), ""));
            }
            sleep(pollIntervalMs);
        }
        throw new IllegalStateException("face-swap generation timed out after " + timeoutMs + " ms.");
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
            throw new IllegalStateException("Interrupted while waiting for face-swap generation.", ex);
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
