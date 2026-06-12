package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StudioPolishVideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StudioPolishVideoGenerationService.class);
    private static final String DEFAULT_VIDEO_MIME_TYPE = "video/mp4";
    private static final int VIDEO_RESPONSE_MAX_IN_MEMORY_BYTES = 512 * 1024 * 1024;

    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;
    private final CreatorAiPricingService pricingService;
    private final GoogleVeoVideoGenerationService googleVeoVideoGenerationService;
    private final WebClient webClient;

    public StudioPolishVideoGenerationService(
            CreatorProperties properties,
            ObjectMapper objectMapper,
            CreatorAiPricingService pricingService,
            GoogleVeoVideoGenerationService googleVeoVideoGenerationService,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.pricingService = pricingService;
        this.googleVeoVideoGenerationService = googleVeoVideoGenerationService;
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(VIDEO_RESPONSE_MAX_IN_MEMORY_BYTES))
                .build();
    }

    public GeneratedVideo generate(StudioPolishVideoRequest request) {
        String provider = normalizeProvider(request.provider());
        String model = modelForProvider(provider, request.model());
        log.info(
                "Studio Polish provider selected provider={} requestedProvider={} model={} requestedModel={} providerMode={} seed={} takeId={} shotNumber={} sourceVideoContentType={} referenceImageBytes={} promptChars={} promptSnippet=\"{}\"",
                provider,
                blankToDefault(request.provider(), ""),
                model,
                blankToDefault(request.model(), ""),
                blankToDefault(request.providerMode(), ""),
                request.seed(),
                request.takeId(),
                request.shotNumber(),
                blankToDefault(request.sourceVideoContentType(), ""),
                request.referenceImageBytes() == null ? 0 : request.referenceImageBytes().length,
                blankToDefault(request.prompt(), "").length(),
                logSnippet(request.prompt(), 900)
        );
        return switch (provider) {
            case "luma" -> generateWithLuma(request);
            case "runway" -> generateWithRunway(request);
            case "decart" -> generateWithDecart(request);
            case "google_veo" -> generateWithGoogleVeo(request);
            default -> throw new IllegalArgumentException("Unsupported studio polish video provider: " + request.provider());
        };
    }

    public String normalizeProvider(String provider) {
        String normalized = blankToDefault(provider, properties.getAi().getStudioPolishVideoProvider())
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .trim();
        if (normalized.isBlank()) {
            return "google_veo";
        }
        if (normalized.equals("veo") || normalized.equals("google") || normalized.equals("gemini_veo")) {
            return "google_veo";
        }
        if (normalized.equals("luma_modify_video") || normalized.equals("luma_ai")) {
            return "luma";
        }
        if (normalized.equals("runway_aleph") || normalized.equals("runwayml")) {
            return "runway";
        }
        if (normalized.equals("decart_vton") || normalized.equals("lucy_vton") || normalized.equals("lucy_vton_3")) {
            return "decart";
        }
        return normalized;
    }

    public String modelForProvider(String provider, String requestedModel) {
        String normalized = normalizeProvider(provider);
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel;
        }
        return switch (normalized) {
            case "luma" -> blankToDefault(properties.getAi().getLumaVideoModel(), "ray-flash-2");
            case "runway" -> blankToDefault(properties.getAi().getRunwayVideoModel(), "aleph2");
            case "decart" -> blankToDefault(properties.getAi().getDecartVideoModel(), "lucy-vton-3");
            case "google_veo" -> blankToDefault(properties.getAi().getGeminiVideoModel(), "veo-3.1-generate-preview");
            default -> requestedModel;
        };
    }

    public String modeForProvider(String provider, String requestedMode) {
        String normalized = normalizeProvider(provider);
        if (requestedMode != null && !requestedMode.isBlank()) {
            return requestedMode;
        }
        if ("luma".equals(normalized)) {
            return blankToDefault(properties.getAi().getLumaVideoMode(), "adhere_2");
        }
        return "";
    }

    private GeneratedVideo generateWithGoogleVeo(StudioPolishVideoRequest request) {
        if (request.referenceImageBytes() == null || request.referenceImageBytes().length == 0) {
            throw new IllegalArgumentException("Google Veo Studio Polish needs a selected reference frame. Luma/Runway can edit directly from the uploaded video.");
        }
        String model = modelForProvider("google_veo", request.model());
        log.info(
                "Google Veo Studio Polish request model={} takeId={} shotNumber={} screenType={} referenceBytes={} promptChars={} promptSnippet=\"{}\"",
                model,
                request.takeId(),
                request.shotNumber(),
                request.screenType(),
                request.referenceImageBytes().length,
                blankToDefault(request.prompt(), "").length(),
                logSnippet(request.prompt(), 900)
        );
        GoogleVeoVideoGenerationService.GeneratedVideo generated = googleVeoVideoGenerationService.generateFromImage(
                request.prompt(),
                request.referenceImageBytes(),
                request.referenceImageContentType(),
                request.screenType()
        );
        return new GeneratedVideo(
                generated.bytes(),
                generated.contentType(),
                generated.metadata(),
                generated.operationName(),
                generated.providerRequest(),
                generated.providerResponse()
        );
    }

    private GeneratedVideo generateWithLuma(StudioPolishVideoRequest request) {
        String apiKey = requireSecret(properties.getAi().getLumaApiKey(), "LUMA_API_KEY");
        String model = modelForProvider("luma", request.model());
        String mode = modeForProvider("luma", request.providerMode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generation_type", "modify_video");
        body.put("prompt", truncate(request.prompt(), 4000));
        body.put("model", model);
        body.put("mode", mode);
        body.put("media", Map.of("url", request.sourceVideoUrl()));
        if (request.referenceImageUrl() != null && !request.referenceImageUrl().isBlank()) {
            body.put("first_frame", Map.of("url", request.referenceImageUrl()));
        }

        String startUrl = trimTrailingSlash(blankToDefault(properties.getAi().getLumaBaseUrl(), "https://api.lumalabs.ai/dream-machine/v1"))
                + "/generations/video/modify";
        log.info(
                "Luma Modify Video request model={} mode={} takeId={} shotNumber={} firstFrameUsed={} promptChars={} promptSnippet=\"{}\"",
                model,
                mode,
                request.takeId(),
                request.shotNumber(),
                request.referenceImageUrl() != null && !request.referenceImageUrl().isBlank(),
                blankToDefault(request.prompt(), "").length(),
                logSnippet(request.prompt(), 900)
        );
        JsonNode startResponse = postJson(startUrl, apiKey, null, body, "Luma Modify Video");
        String generationId = firstText(textAt(startResponse, "id"), textAt(startResponse, "generation", "id"));
        if (generationId.isBlank()) {
            throw new IllegalStateException("Luma Modify Video did not return a generation id. response=" + truncate(String.valueOf(startResponse), 1200));
        }
        log.info("Luma Modify Video accepted generationId={} model={} mode={} takeId={} shotNumber={}", generationId, model, mode, request.takeId(), request.shotNumber());

        JsonNode completed = waitForLumaGeneration(apiKey, generationId);
        String videoUrl = firstText(
                textAt(completed, "assets", "video"),
                textAt(completed, "output", "0", "url"),
                textAt(completed, "output", "0"),
                textAt(completed, "video", "url")
        );
        if (videoUrl.isBlank()) {
            throw new IllegalStateException("Luma generation completed without video URL. response=" + truncate(String.valueOf(completed), 1200));
        }
        byte[] videoBytes = downloadBytes(videoUrl, "Luma output video");
        log.info("Luma Modify Video completed generationId={} model={} mode={} outputBytes={}", generationId, model, mode, videoBytes.length);
        Map<String, Object> metadata = providerMetadata(
                "luma",
                model,
                generationId,
                videoUrl,
                "visual_video_to_video_only_audio_not_cleaned",
                pricingService.estimateLumaModifyVideoCall(
                        model,
                        positiveDecimal(request.durationSeconds(), BigDecimal.valueOf(8)),
                        request.screenType(),
                        request.resolution(),
                        "studio_polish_luma_modify_video"
                )
        );
        metadata.put("mode", mode);
        metadata.put("firstFrameUsed", request.referenceImageUrl() != null && !request.referenceImageUrl().isBlank());
        return new GeneratedVideo(videoBytes, DEFAULT_VIDEO_MIME_TYPE, metadata, generationId, body, toMap(completed));
    }

    private GeneratedVideo generateWithRunway(StudioPolishVideoRequest request) {
        String apiKey = requireSecret(properties.getAi().getRunwayApiSecret(), "RUNWAY_API_SECRET");
        String model = modelForProvider("runway", request.model());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("videoUri", request.sourceVideoUrl());
        body.put("promptText", truncate(request.prompt(), 1000));
        body.put("ratio", runwayRatio(request.screenType()));
        Long seed = runwaySeed(request.seed());
        if (seed != null) {
            body.put("seed", seed);
        }
        body.put("contentModeration", Map.of("publicFigureThreshold", "auto"));
        if (request.referenceImageUrl() != null && !request.referenceImageUrl().isBlank()) {
            body.put("references", List.of(Map.of(
                    "type", "image",
                    "uri", request.referenceImageUrl()
            )));
        }

        String startUrl = trimTrailingSlash(blankToDefault(properties.getAi().getRunwayBaseUrl(), "https://api.dev.runwayml.com"))
                + "/v1/video_to_video";
        log.info(
                "Runway video-to-video request model={} ratio={} seed={} apiVersion={} takeId={} shotNumber={} sourceVideoContentType={} referenceImageUsed={} promptChars={} promptSnippet=\"{}\"",
                model,
                body.get("ratio"),
                seed,
                blankToDefault(properties.getAi().getRunwayApiVersion(), ""),
                request.takeId(),
                request.shotNumber(),
                blankToDefault(request.sourceVideoContentType(), ""),
                request.referenceImageUrl() != null && !request.referenceImageUrl().isBlank(),
                blankToDefault(request.prompt(), "").length(),
                logSnippet(request.prompt(), 900)
        );
        JsonNode startResponse = postJson(startUrl, apiKey, properties.getAi().getRunwayApiVersion(), body, "Runway video_to_video");
        String taskId = firstText(textAt(startResponse, "id"), textAt(startResponse, "task", "id"));
        if (taskId.isBlank()) {
            throw new IllegalStateException("Runway video_to_video did not return a task id. response=" + truncate(String.valueOf(startResponse), 1200));
        }
        log.info("Runway video-to-video accepted taskId={} model={} seed={} ratio={} takeId={} shotNumber={}", taskId, model, seed, body.get("ratio"), request.takeId(), request.shotNumber());

        JsonNode completed = waitForRunwayTask(apiKey, taskId);
        String videoUrl = firstText(
                textAt(completed, "output", "0"),
                textAt(completed, "output", "0", "url"),
                textAt(completed, "artifacts", "0", "url"),
                textAt(completed, "video", "url")
        );
        if (videoUrl.isBlank()) {
            throw new IllegalStateException("Runway task succeeded without output video URL. response=" + truncate(String.valueOf(completed), 1200));
        }
        byte[] videoBytes = downloadBytes(videoUrl, "Runway output video");
        log.info("Runway video-to-video completed taskId={} model={} seed={} outputBytes={}", taskId, model, seed, videoBytes.length);
        Map<String, Object> metadata = providerMetadata(
                "runway",
                model,
                taskId,
                videoUrl,
                "visual_video_to_video_only_audio_not_cleaned",
                pricingService.estimateRunwayVideoToVideoCall(
                        model,
                        positiveDecimal(request.durationSeconds(), BigDecimal.valueOf(5)),
                        "studio_polish_runway_video_to_video"
                )
        );
        metadata.put("ratio", body.get("ratio"));
        if (seed != null) {
            metadata.put("seed", seed);
        }
        metadata.put("referenceImageUsed", request.referenceImageUrl() != null && !request.referenceImageUrl().isBlank());
        return new GeneratedVideo(videoBytes, DEFAULT_VIDEO_MIME_TYPE, metadata, taskId, body, toMap(completed));
    }

    private GeneratedVideo generateWithDecart(StudioPolishVideoRequest request) {
        String apiKey = requireSecret(properties.getAi().getDecartApiKey(), "DECART_API_KEY");
        String model = modelForProvider("decart", request.model());
        String resolution = blankToDefault(properties.getAi().getDecartVideoResolution(), "720p");
        Long seed = runwaySeed(request.seed());
        String prompt = truncate(request.prompt(), 4000);
        byte[] sourceVideoBytes = downloadBytes(request.sourceVideoUrl(), "Decart input source video");
        Map<String, Object> providerRequest = new LinkedHashMap<>();
        providerRequest.put("model", model);
        providerRequest.put("prompt", prompt);
        providerRequest.put("resolution", resolution);
        providerRequest.put("seed", seed);
        providerRequest.put("enhancePrompt", properties.getAi().isDecartEnhancePrompt());
        providerRequest.put("sourceVideoContentType", request.sourceVideoContentType());
        providerRequest.put("sourceVideoBytes", sourceVideoBytes.length);
        providerRequest.put("referenceImageUsed", false);

        String startUrl = trimTrailingSlash(blankToDefault(properties.getAi().getDecartBaseUrl(), "https://api.decart.ai/v1"))
                + "/jobs/" + model;
        log.info(
                "Decart virtual try-on request model={} resolution={} seed={} enhancePrompt={} takeId={} shotNumber={} inputBytes={} promptChars={} promptSnippet=\"{}\"",
                model,
                resolution,
                seed,
                properties.getAi().isDecartEnhancePrompt(),
                request.takeId(),
                request.shotNumber(),
                sourceVideoBytes.length,
                blankToDefault(prompt, "").length(),
                logSnippet(prompt, 900)
        );
        JsonNode startResponse = postDecartMultipartVideo(startUrl, apiKey, sourceVideoBytes, request.sourceVideoContentType(), providerRequest, "Decart virtual try-on");
        String jobId = firstText(textAt(startResponse, "job_id"), textAt(startResponse, "jobId"), textAt(startResponse, "id"));
        if (jobId.isBlank()) {
            throw new IllegalStateException("Decart virtual try-on did not return a job id. response=" + truncate(String.valueOf(startResponse), 1200));
        }
        log.info("Decart virtual try-on accepted jobId={} model={} seed={} takeId={} shotNumber={}", jobId, model, seed, request.takeId(), request.shotNumber());

        JsonNode completed = waitForDecartJob(apiKey, jobId);
        byte[] videoBytes = downloadDecartJobContent(apiKey, jobId);
        log.info("Decart virtual try-on completed jobId={} model={} seed={} outputBytes={}", jobId, model, seed, videoBytes.length);
        Map<String, Object> metadata = providerMetadata(
                "decart",
                model,
                jobId,
                trimTrailingSlash(blankToDefault(properties.getAi().getDecartBaseUrl(), "https://api.decart.ai/v1")) + "/jobs/" + jobId + "/content",
                "visual_wardrobe_try_on_only_audio_not_cleaned",
                pricingService.estimateDecartVirtualTryOnCall(
                        model,
                        positiveDecimal(request.durationSeconds(), BigDecimal.valueOf(5)),
                        resolution,
                        "studio_polish_decart_virtual_try_on"
                )
        );
        metadata.put("resolution", resolution);
        metadata.put("seed", seed);
        metadata.put("enhancePrompt", properties.getAi().isDecartEnhancePrompt());
        metadata.put("referenceImageUsed", false);
        metadata.put("promptPattern", "substitute_or_add_wardrobe_prompt");
        return new GeneratedVideo(videoBytes, DEFAULT_VIDEO_MIME_TYPE, metadata, jobId, providerRequest, toMap(completed));
    }

    private JsonNode waitForLumaGeneration(String apiKey, String generationId) {
        long timeoutMs = Math.max(60000, properties.getAi().getLumaVideoTimeoutMs());
        long pollIntervalMs = Math.max(1000, properties.getAi().getLumaVideoPollIntervalMs());
        String pollUrl = trimTrailingSlash(blankToDefault(properties.getAi().getLumaBaseUrl(), "https://api.lumalabs.ai/dream-machine/v1"))
                + "/generations/" + generationId;
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        String lastState = "";
        while (Instant.now().isBefore(deadline)) {
            JsonNode response = getJson(pollUrl, apiKey, null, "Luma generation status");
            String state = firstText(textAt(response, "state"), textAt(response, "status")).toLowerCase(Locale.ROOT);
            if (!state.equals(lastState)) {
                log.info("Luma Modify Video status generationId={} state={}", generationId, state);
                lastState = state;
            }
            if (state.equals("completed") || state.equals("succeeded") || state.equals("success")) {
                return response;
            }
            if (state.equals("failed") || state.equals("canceled") || state.equals("cancelled")) {
                throw new IllegalStateException("Luma generation failed state=%s reason=%s"
                        .formatted(state, firstText(textAt(response, "failure_reason"), textAt(response, "error", "message"))));
            }
            sleep(pollIntervalMs, "Luma generation");
        }
        throw new IllegalStateException("Luma generation timed out after " + timeoutMs + " ms.");
    }

    private JsonNode waitForRunwayTask(String apiKey, String taskId) {
        long timeoutMs = Math.max(60000, properties.getAi().getRunwayVideoTimeoutMs());
        long pollIntervalMs = Math.max(1000, properties.getAi().getRunwayVideoPollIntervalMs());
        String pollUrl = trimTrailingSlash(blankToDefault(properties.getAi().getRunwayBaseUrl(), "https://api.dev.runwayml.com"))
                + "/v1/tasks/" + taskId;
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        String lastStatus = "";
        while (Instant.now().isBefore(deadline)) {
            JsonNode response = getJson(pollUrl, apiKey, properties.getAi().getRunwayApiVersion(), "Runway task status");
            String status = firstText(textAt(response, "status")).toUpperCase(Locale.ROOT);
            if (!status.equals(lastStatus)) {
                log.info("Runway task status taskId={} status={}", taskId, status);
                lastStatus = status;
            }
            if (status.equals("SUCCEEDED") || status.equals("SUCCESS")) {
                return response;
            }
            if (status.equals("FAILED") || status.equals("CANCELED") || status.equals("CANCELLED")) {
                throw new IllegalStateException("Runway task failed status=%s error=%s"
                        .formatted(status, firstText(textAt(response, "failure"), textAt(response, "error"), textAt(response, "errorMessage"))));
            }
            sleep(pollIntervalMs, "Runway task");
        }
        throw new IllegalStateException("Runway task timed out after " + timeoutMs + " ms.");
    }

    private JsonNode waitForDecartJob(String apiKey, String jobId) {
        long timeoutMs = Math.max(60000, properties.getAi().getDecartVideoTimeoutMs());
        long pollIntervalMs = Math.max(1000, properties.getAi().getDecartVideoPollIntervalMs());
        String pollUrl = trimTrailingSlash(blankToDefault(properties.getAi().getDecartBaseUrl(), "https://api.decart.ai/v1"))
                + "/jobs/" + jobId;
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        String lastStatus = "";
        while (Instant.now().isBefore(deadline)) {
            JsonNode response = getJsonWithHeader(pollUrl, "X-API-KEY", apiKey, "Decart job status");
            String status = firstText(textAt(response, "status"), textAt(response, "state")).toLowerCase(Locale.ROOT);
            if (!status.equals(lastStatus)) {
                log.info("Decart virtual try-on status jobId={} status={}", jobId, status);
                lastStatus = status;
            }
            if (status.equals("completed") || status.equals("succeeded") || status.equals("success")) {
                return response;
            }
            if (status.equals("failed") || status.equals("canceled") || status.equals("cancelled")) {
                throw new IllegalStateException("Decart job failed status=%s error=%s"
                        .formatted(status, firstText(textAt(response, "failure_reason"), textAt(response, "error"), textAt(response, "error", "message"))));
            }
            sleep(pollIntervalMs, "Decart job");
        }
        throw new IllegalStateException("Decart virtual try-on timed out after " + timeoutMs + " ms.");
    }

    private JsonNode postDecartMultipartVideo(
            String url,
            String apiKey,
            byte[] videoBytes,
            String contentType,
            Map<String, Object> request,
            String label
    ) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            MediaType mediaType = parseMediaType(contentType, MediaType.valueOf(DEFAULT_VIDEO_MIME_TYPE));
            builder.part("data", new ByteArrayResource(videoBytes) {
                @Override
                public String getFilename() {
                    return "source.mp4";
                }
            }).filename("source.mp4").contentType(mediaType);
            builder.part("prompt", blankToDefault(request.get("prompt") == null ? "" : String.valueOf(request.get("prompt")), ""));
            builder.part("resolution", blankToDefault(request.get("resolution") == null ? "" : String.valueOf(request.get("resolution")), "720p"));
            Object seed = request.get("seed");
            if (seed != null) {
                builder.part("seed", String.valueOf(seed));
            }
            builder.part("enhance_prompt", String.valueOf(properties.getAi().isDecartEnhancePrompt()));
            return webClient.post()
                    .uri(url)
                    .header("X-API-KEY", apiKey)
                    .header("Accept", "application/json")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 60000)));
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s request failed HTTP %s. body=%s"
                    .formatted(label, ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 3000)), ex);
        }
    }

    private JsonNode postJson(String url, String bearerToken, String runwayVersion, Map<String, Object> body, String label) {
        try {
            WebClient.RequestBodySpec request = webClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (runwayVersion != null && !runwayVersion.isBlank()) {
                request.header("X-Runway-Version", runwayVersion);
            }
            return request
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(properties.getAi().getTimeoutMs()));
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s request failed HTTP %s. body=%s"
                    .formatted(label, ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 3000)), ex);
        }
    }

    private JsonNode getJson(String url, String bearerToken, String runwayVersion, String label) {
        try {
            WebClient.RequestHeadersSpec<?> request = webClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json");
            if (runwayVersion != null && !runwayVersion.isBlank()) {
                request.header("X-Runway-Version", runwayVersion);
            }
            return request
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 60000)));
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s request failed HTTP %s. body=%s"
                    .formatted(label, ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 3000)), ex);
        }
    }

    private JsonNode getJsonWithHeader(String url, String headerName, String headerValue, String label) {
        try {
            return webClient.get()
                    .uri(url)
                    .header(headerName, headerValue)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 60000)));
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s request failed HTTP %s. body=%s"
                    .formatted(label, ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 3000)), ex);
        }
    }

    private byte[] downloadDecartJobContent(String apiKey, String jobId) {
        String url = trimTrailingSlash(blankToDefault(properties.getAi().getDecartBaseUrl(), "https://api.decart.ai/v1"))
                + "/jobs/" + jobId + "/content";
        try {
            byte[] bytes = webClient
                    .get()
                    .uri(URI.create(url))
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 180000)));
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("Decart output video was empty.");
            }
            return bytes;
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("Decart output video download failed HTTP %s. body=%s"
                    .formatted(ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 1200)), ex);
        }
    }

    private byte[] downloadBytes(String url, String label) {
        try {
            byte[] bytes = webClient
                    .get()
                    .uri(URI.create(url))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 180000)));
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException(label + " was empty.");
            }
            return bytes;
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s download failed HTTP %s. body=%s"
                    .formatted(label, ex.getStatusCode(), truncate(ex.getResponseBodyAsString(), 1200)), ex);
        }
    }

    private Map<String, Object> providerMetadata(String provider, String model, String operationId, String videoUrl, String note, Map<String, Object> costMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider);
        metadata.put("model", model);
        metadata.put("operationName", operationId);
        metadata.put("videoUri", videoUrl);
        metadata.put("nativeAudioRequested", false);
        metadata.put("audioPolicy", "enhance_or_mix_audio_separately_after_video_polish");
        metadata.put("note", note);
        metadata.put("costMetadata", costMetadata);
        return metadata;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private String runwayRatio(String screenType) {
        return "horizontal".equalsIgnoreCase(screenType) ? "1280:720" : "720:1280";
    }

    private Long runwaySeed(Long seed) {
        if (seed == null) {
            return null;
        }
        if (seed < 0 || seed > 4_294_967_295L) {
            throw new IllegalArgumentException("Runway seed must be between 0 and 4294967295.");
        }
        return seed;
    }

    private MediaType parseMediaType(String contentType, MediaType fallback) {
        if (contentType == null || contentType.isBlank()) {
            return fallback;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private BigDecimal positiveDecimal(String value, BigDecimal fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            BigDecimal parsed = new BigDecimal(value.trim().replace("s", ""));
            return parsed.signum() <= 0 ? fallback : parsed;
        } catch (NumberFormatException ex) {
            return fallback;
        }
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

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String requireSecret(String value, String envName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(envName + " is not configured for selected Studio Polish provider.");
        }
        return value;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    private String logSnippet(String value, int maxLength) {
        String text = blankToDefault(value, "").replaceAll("\\s+", " ").trim();
        return truncate(text, maxLength);
    }

    private void sleep(long millis, String label) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + label + ".", ex);
        }
    }

    public record StudioPolishVideoRequest(
            String provider,
            String model,
            String providerMode,
            String prompt,
            String sourceVideoUrl,
            String sourceVideoContentType,
            String referenceImageUrl,
            byte[] referenceImageBytes,
            String referenceImageContentType,
            String screenType,
            String resolution,
            String durationSeconds,
            String takeId,
            int shotNumber,
            Long seed
    ) {
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
