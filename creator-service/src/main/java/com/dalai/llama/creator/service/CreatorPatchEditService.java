package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Backend for the Editor's "Generate AI edit": cuts a selection, sends it out for a
 * video-to-video edit, patches the result back in. Provider/model is entirely a
 * backend decision (the UI only ever shows "Provider"), so the request that reaches
 * this service is model-agnostic - {@code provider}/{@code model} coming from the
 * client are advisory only. Today the only implemented route is Kling, called through
 * fal.ai's queue API - the exact same queue.fal.run infrastructure and FAL_KEY auth
 * already used for the "seedance" screenplay video provider
 * ({@link ScreenplayVideoProviderGenerationService}), just pointed at a Kling model
 * endpoint instead of a Seedance one. Model slug fal-ai/kling-video/o3/pro/video-to-video/edit
 * confirmed against fal.ai's published model catalog (billed per second of output);
 * override via PATCH_EDITOR_FAL_KLING_MODEL if that changes. The exact request/response
 * field names below (video_url/prompt/negative_prompt/elements[].image_url in,
 * response.video.url out) follow fal.ai's documented queue conventions and this
 * endpoint's own playground (video is @Video1, each reference image is @Element1,
 * @Element2… that the prompt text can point at) but were not exercised against a live
 * request with real credentials - worth a smoke test with FAL_KEY set before shipping,
 * especially the multi-element shape.
 */
@Service
public class CreatorPatchEditService {

    private static final Logger log = LoggerFactory.getLogger(CreatorPatchEditService.class);
    private static final String JOB_TYPE = "PATCH_EDITOR_VIDEO_EDIT";
    private static final long MAX_CLIP_BYTES = 200L * 1024L * 1024L;
    private static final long MAX_REFERENCE_IMAGE_BYTES = 15L * 1024L * 1024L;
    private static final int MAX_REFERENCE_IMAGES = 4;
    private static final long MAX_REFERENCE_VIDEO_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_REFERENCE_VIDEOS = 1;

    private final AssetStorageService assetStorageService;
    private final GenerationJobService generationJobService;
    private final TaskExecutor taskExecutor;
    private final WebClient webClient;
    private final String falKey;
    private final String falBaseUrl;
    private final String klingModelEndpoint;
    private final long pollIntervalMs;
    private final long timeoutMs;

    public CreatorPatchEditService(
            AssetStorageService assetStorageService,
            GenerationJobService generationJobService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor,
            WebClient.Builder webClientBuilder,
            @Value("${FAL_KEY:${CREATOR_FAL_KEY:}}") String falKey,
            @Value("${PATCH_EDITOR_FAL_BASE_URL:https://queue.fal.run}") String falBaseUrl,
            @Value("${PATCH_EDITOR_FAL_KLING_MODEL:fal-ai/kling-video/o3/pro/video-to-video/edit}") String klingModelEndpoint,
            @Value("${PATCH_EDITOR_VIDEO_POLL_INTERVAL_MS:5000}") long pollIntervalMs,
            @Value("${PATCH_EDITOR_VIDEO_TIMEOUT_MS:900000}") long timeoutMs
    ) {
        this.assetStorageService = assetStorageService;
        this.generationJobService = generationJobService;
        this.taskExecutor = taskExecutor;
        this.webClient = webClientBuilder.clone().build();
        this.falKey = falKey;
        this.falBaseUrl = trimTrailingSlash(falBaseUrl);
        this.klingModelEndpoint = klingModelEndpoint;
        this.pollIntervalMs = Math.max(1000, pollIntervalMs);
        this.timeoutMs = Math.max(60000, timeoutMs);
    }

    public GenerationJobResponse startGenerate(
            MultipartFile file,
            String prompt,
            String negativePrompt,
            List<MultipartFile> referenceImages,
            List<MultipartFile> referenceVideos,
            Double startSeconds,
            Double endSeconds,
            String tenantId,
            String userId
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the clip to edit.");
        }
        if (file.getSize() > MAX_CLIP_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clip must be 200 MB or smaller.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Describe the edit you want.");
        }
        List<MultipartFile> safeReferenceImages = referenceImages == null ? List.of() : referenceImages.stream()
                .filter(item -> item != null && !item.isEmpty())
                .toList();
        if (safeReferenceImages.size() > MAX_REFERENCE_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use at most " + MAX_REFERENCE_IMAGES + " reference images.");
        }
        for (MultipartFile image : safeReferenceImages) {
            if (image.getSize() > MAX_REFERENCE_IMAGE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each reference image must be 15 MB or smaller.");
            }
        }
        List<MultipartFile> safeReferenceVideos = referenceVideos == null ? List.of() : referenceVideos.stream()
                .filter(item -> item != null && !item.isEmpty())
                .toList();
        if (safeReferenceVideos.size() > MAX_REFERENCE_VIDEOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use at most " + MAX_REFERENCE_VIDEOS + " reference video.");
        }
        for (MultipartFile video : safeReferenceVideos) {
            if (video.getSize() > MAX_REFERENCE_VIDEO_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The reference video must be 100 MB or smaller.");
            }
        }
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");

        String clipContentType = defaultString(file.getContentType(), "video/mp4");
        AssetStorageService.StoredObject clip = uploadTemp(file, "clip", clipContentType);
        List<AssetStorageService.StoredObject> references = new ArrayList<>();
        for (int index = 0; index < safeReferenceImages.size(); index++) {
            MultipartFile image = safeReferenceImages.get(index);
            references.add(uploadTemp(image, "reference-" + (index + 1), defaultString(image.getContentType(), "image/png")));
        }
        List<AssetStorageService.StoredObject> videoReferences = new ArrayList<>();
        for (int index = 0; index < safeReferenceVideos.size(); index++) {
            MultipartFile video = safeReferenceVideos.get(index);
            videoReferences.add(uploadTemp(video, "reference-video-" + (index + 1), defaultString(video.getContentType(), "video/mp4")));
        }

        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("provider", "kling");
        jobInput.put("prompt", prompt);
        jobInput.put("negativePrompt", defaultString(negativePrompt, ""));
        jobInput.put("startSeconds", startSeconds);
        jobInput.put("endSeconds", endSeconds);
        jobInput.put("clipBucket", clip.bucket());
        jobInput.put("clipObjectKey", clip.objectKey());
        jobInput.put("referenceImageCount", references.size());
        jobInput.put("referenceVideoCount", videoReferences.size());
        CreatorGenerationJob job = generationJobService.startGenerationJob(JOB_TYPE, safeTenantId, safeUserId, null, jobInput);

        taskExecutor.execute(() -> runEdit(job.getId(), clip, references, videoReferences, prompt, negativePrompt, safeTenantId, safeUserId));

        return generationJobService.toResponse(job);
    }

    private void runEdit(
            UUID jobId,
            AssetStorageService.StoredObject clip,
            List<AssetStorageService.StoredObject> references,
            List<AssetStorageService.StoredObject> videoReferences,
            String prompt,
            String negativePrompt,
            String tenantId,
            String userId
    ) {
        try {
            if (falKey.isBlank()) {
                throw new IllegalStateException("FAL_KEY is not configured - cannot reach the video edit provider.");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("video_url", clip.signedUrl());
            body.put("prompt", prompt);
            if (negativePrompt != null && !negativePrompt.isBlank()) {
                body.put("negative_prompt", negativePrompt);
            }
            // fal.ai's playground for this exact endpoint (o3/pro/video-to-video/edit) shows
            // the video as @Video1 and each uploaded reference as @Element1, @Element2… that
            // the prompt text can point at. The "elements" array-of-objects shape below is
            // this service's best-effort match to that UI (not exercised against a live
            // request/real FAL_KEY) - verify against fal.ai's schema for this model if edits
            // with multiple references don't come back right. The reference video follows the
            // same best-effort "elements" shape as images (video_url instead of image_url,
            // surfaced to the prompt as @RefVideo1 in the UI) - equally unverified against a
            // live request; if it's rejected, check whether this model wants reference videos
            // as a separate top-level field instead of mixed into elements.
            List<Map<String, Object>> elements = new ArrayList<>();
            for (AssetStorageService.StoredObject reference : references) {
                elements.add(Map.of("image_url", reference.signedUrl()));
            }
            for (AssetStorageService.StoredObject videoReference : videoReferences) {
                elements.add(Map.of("video_url", videoReference.signedUrl()));
            }
            if (!elements.isEmpty()) {
                body.put("elements", elements);
            }

            String submitUrl = falBaseUrl + "/" + klingModelEndpoint;
            log.info("Patch editor Kling video edit request jobId={} model={} clipBytes={} referenceImageCount={} referenceVideoCount={} promptChars={}",
                    jobId, klingModelEndpoint, clip.sizeBytes(), references.size(), videoReferences.size(), prompt.length());
            JsonNode started = postJson(submitUrl, body, "Kling video edit submit");
            String requestId = firstText(started, "request_id");
            if (requestId.isBlank()) {
                throw new IllegalStateException("fal.ai did not return a request id. response=" + started);
            }
            String statusUrl = firstText(started, "status_url");
            if (statusUrl.isBlank()) {
                statusUrl = submitUrl + "/requests/" + requestId + "/status";
            }
            String responseUrl = firstText(started, "response_url");
            if (responseUrl.isBlank()) {
                responseUrl = submitUrl + "/requests/" + requestId;
            }

            JsonNode result = pollUntilComplete(statusUrl, responseUrl, jobId, requestId);
            String resultVideoUrl = firstVideoUrl(result);
            if (resultVideoUrl.isBlank()) {
                throw new IllegalStateException("Kling video edit completed without a result video URL. response=" + result);
            }

            byte[] resultBytes = downloadBytes(resultVideoUrl);
            String objectKey = "patch-editor/edits/%s/result.mp4".formatted(UUID.randomUUID());
            AssetStorageService.StoredObject resultAsset = assetStorageService.uploadCreatorAsset(
                    objectKey, resultBytes, "video/mp4", Duration.ofDays(7)
            );

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("videoUrl", resultAsset.signedUrl());
            output.put("finalVideoUrl", resultAsset.signedUrl());
            output.put("clipUrl", resultAsset.signedUrl());
            output.put("publicUrl", resultAsset.signedUrl());
            output.put("signedUrl", resultAsset.signedUrl());
            output.put("provider", "kling");
            output.put("model", klingModelEndpoint);
            output.put("falRequestId", requestId);
            output.put("completedAt", OffsetDateTime.now().toString());
            generationJobService.completeGenerationJob(jobId, output);
            log.info("Patch editor Kling video edit completed jobId={} requestId={} resultBytes={}", jobId, requestId, resultBytes.length);
        } catch (RuntimeException ex) {
            log.error("Patch editor video edit failed jobId={}", jobId, ex);
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
        }
    }

    private JsonNode pollUntilComplete(String statusUrl, String responseUrl, UUID jobId, String requestId) {
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        String lastStatus = "";
        while (Instant.now().isBefore(deadline)) {
            JsonNode status = getJson(statusUrl, "Kling video edit status");
            String state = firstText(status, "status").toUpperCase(Locale.ROOT);
            if (!state.equals(lastStatus)) {
                log.info("Patch editor Kling video edit status jobId={} requestId={} status={}", jobId, requestId, state);
                lastStatus = state;
            }
            if (state.equals("COMPLETED") || state.equals("SUCCEEDED") || state.equals("SUCCESS")) {
                return getJson(responseUrl, "Kling video edit result");
            }
            if (state.equals("FAILED") || state.equals("ERROR") || state.equals("CANCELED") || state.equals("CANCELLED")) {
                throw new IllegalStateException("Kling video edit failed status=" + state + " error=" + firstText(status, "error"));
            }
            sleep(pollIntervalMs);
        }
        throw new IllegalStateException("Kling video edit timed out after " + timeoutMs + " ms.");
    }

    private AssetStorageService.StoredObject uploadTemp(MultipartFile file, String label, String contentType) {
        try {
            String objectKey = "patch-editor/edits/%s/%s%s".formatted(UUID.randomUUID(), label, extensionFor(contentType));
            return assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, Duration.ofHours(6));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded " + label + ".", ex);
        }
    }

    private JsonNode postJson(String url, Map<String, Object> body, String label) {
        try {
            return webClient.post()
                    .uri(url)
                    .header("Authorization", "Key " + falKey)
                    .header("Accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s failed HTTP %s. body=%s"
                    .formatted(label, ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 2000)), ex);
        }
    }

    private JsonNode getJson(String url, String label) {
        try {
            return webClient.get()
                    .uri(url)
                    .header("Authorization", "Key " + falKey)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
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

    private String firstVideoUrl(JsonNode result) {
        List<String> candidates = List.of("video.url", "video_url", "output.video.url", "data.video.url");
        for (String path : candidates) {
            String value = firstText(result, path.split("\\."));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String firstText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String segment : path) {
            if (current == null) return "";
            current = current.path(segment);
        }
        return current == null || current.isMissingNode() || current.isNull() ? "" : current.asText("");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Kling video edit.", ex);
        }
    }

    private String extensionFor(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("png")) return ".png";
        if (normalized.contains("webp")) return ".webp";
        if (normalized.contains("jpeg") || normalized.contains("jpg")) return ".jpg";
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        return ".mp4";
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
