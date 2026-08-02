package com.dalai.llama.creator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class ScreenplayVideoProviderGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ScreenplayVideoProviderGenerationService.class);
    private static final String DEFAULT_VIDEO_MIME_TYPE = "video/mp4";
    private static final String DEFAULT_IMAGE_MIME_TYPE = "image/png";
    private static final int VIDEO_RESPONSE_MAX_IN_MEMORY_BYTES = 512 * 1024 * 1024;
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final String AVATAR_METADATA_HEADER = "X-Dalai-Avatar-Metadata";
    private static final String DEFAULT_AVATAR_SCENE_FILE_PATH = "/creator/avatar/scenes/files";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final HttpClient httpClient;
    private final AssetStorageService assetStorageService;
    private final String dalaiLlamaBaseUrlOverride;
    private final Map<String, Semaphore> providerSemaphores = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> providerNextRequestAtMs = new ConcurrentHashMap<>();

    @Autowired
    public ScreenplayVideoProviderGenerationService(ObjectMapper objectMapper, WebClient.Builder webClientBuilder, AssetStorageService assetStorageService) {
        this(objectMapper, webClientBuilder, assetStorageService, "");
    }

    ScreenplayVideoProviderGenerationService(
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder,
            AssetStorageService assetStorageService,
            String dalaiLlamaBaseUrlOverride
    ) {
        this.objectMapper = objectMapper;
        this.assetStorageService = assetStorageService;
        this.dalaiLlamaBaseUrlOverride = firstText(dalaiLlamaBaseUrlOverride);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(VIDEO_RESPONSE_MAX_IN_MEMORY_BYTES))
                .build();
    }

    public GeneratedSceneVideo generate(SceneVideoRequest request) {
        long generationStartedNanos = System.nanoTime();
        ProviderConfig config = providerConfig(request.provider(), request.model());
        long configurationResolvedNanos = System.nanoTime();
        requireProviderReady(config, request);
        long providerValidatedNanos = System.nanoTime();
        AtomicReference<Map<String, Object>> providerRequestRef = new AtomicReference<>(buildProviderRequest(config, request));
        long providerRequestBuiltNanos = System.nanoTime();
        Map<String, Object> preflightCost = estimateCost(
                config,
                request,
                providerRequestRef.get(),
                Map.of(),
                "CONFIGURED_PREFLIGHT"
        );
        long preflightCompletedNanos = System.nanoTime();
        if (!config.allowUnpricedProviderCalls() && decimalValue(preflightCost.get("totalCost")).signum() <= 0) {
            throw new IllegalStateException(
                    "Configure " + config.prefix() + "_VIDEO_RATE_PER_SECOND_USD, "
                            + config.prefix() + "_VIDEO_RATE_PER_CLIP_USD, or "
                            + config.prefix() + "_VIDEO_RATE_PER_MILLION_TOKENS_USD before calling "
                            + config.provider() + " video generation."
            );
        }

        Map<String, Object> preparedRequest = providerRequestRef.get();
        log.info(
                "Screenplay video provider preparation completed runId={} scriptId={} sceneId={} sceneNumber={} provider={} model={} configurationMs={} validationMs={} requestBuildMs={} preflightMs={} preparationTotalMs={} promptChars={} referenceImageUsed={}",
                request.runId(),
                request.scriptId(),
                request.sceneId(),
                request.sceneNumber(),
                config.provider(),
                config.model(),
                TimeUnit.NANOSECONDS.toMillis(configurationResolvedNanos - generationStartedNanos),
                TimeUnit.NANOSECONDS.toMillis(providerValidatedNanos - configurationResolvedNanos),
                TimeUnit.NANOSECONDS.toMillis(providerRequestBuiltNanos - providerValidatedNanos),
                TimeUnit.NANOSECONDS.toMillis(preflightCompletedNanos - providerRequestBuiltNanos),
                TimeUnit.NANOSECONDS.toMillis(preflightCompletedNanos - generationStartedNanos),
                providerPromptForLog(preparedRequest).length(),
                providerRequestUsesReferenceImage(preparedRequest)
        );

        if ("dalai_llama".equals(config.provider())) {
            return withProviderLease(
                    config,
                    () -> generateStreamedDalaiLlamaAvatarScene(
                            config,
                            request,
                            preparedRequest,
                            generationStartedNanos
                    )
            );
        }

        long providerLeaseRequestedNanos = System.nanoTime();
        return withProviderLease(config, () -> {
            long providerLeaseAcquiredNanos = System.nanoTime();
            Map<String, Object> providerRequest = providerRequestRef.get();
            String startUrl = providerStartUrl(config, providerRequest);
            log.info(
                    "Screenplay video provider slot acquired runId={} scriptId={} sceneId={} provider={} leaseWaitMs={} elapsedSinceSceneStartMs={}",
                    request.runId(),
                    request.scriptId(),
                    request.sceneId(),
                    config.provider(),
                    TimeUnit.NANOSECONDS.toMillis(providerLeaseAcquiredNanos - providerLeaseRequestedNanos),
                    TimeUnit.NANOSECONDS.toMillis(providerLeaseAcquiredNanos - generationStartedNanos)
            );
            log.info(
                    "Screenplay video provider request provider={} model={} sceneId={} durationSeconds={} aspectRatio={} seed={} promptChars={} url={}",
                    config.provider(),
                    config.model(),
                    request.sceneId(),
                    request.durationSeconds(),
                    request.aspectRatio(),
                    firstValue(providerRequest.get("seed"), mapValue(providerRequest.get("parameters")).get("seed")),
                    providerPromptForLog(providerRequest).length(),
                    startUrl
            );
            log.info(
                    "Screenplay video provider payload provider={} model={} sceneId={} body={}",
                    config.provider(),
                    config.model(),
                    request.sceneId(),
                    providerPayloadForLog(providerRequest)
            );

            JsonNode startResponse;
            long providerSubmissionStartedNanos = System.nanoTime();
            try {
                final Map<String, Object> initialProviderRequest = providerRequest;
                startResponse = executeProviderStartWithRetries(config, "start " + config.provider() + " video", () ->
                        postJson(startUrl, config, initialProviderRequest)
                );
            } catch (RuntimeException ex) {
                if (!shouldRetryGeminiOmniWithoutVideoConfig(config, ex)) {
                    throw ex;
                }
                providerRequest = geminiOmniMinimalStartRequest(providerRequest);
                providerRequestRef.set(providerRequest);
                log.warn(
                        "Retrying Gemini Omni scene video without generation_config.video_config provider={} model={} sceneId={} reason={}",
                        config.provider(),
                        config.model(),
                        request.sceneId(),
                        truncate(stringValue(ex.getMessage(), ex.getClass().getSimpleName()), 500)
                );
                log.info(
                        "Screenplay video provider fallback payload provider={} model={} sceneId={} body={}",
                        config.provider(),
                        config.model(),
                        request.sceneId(),
                        providerPayloadForLog(providerRequest)
                );
                final Map<String, Object> fallbackProviderRequest = providerRequest;
                startResponse = executeProviderStartWithRetries(config, "start " + config.provider() + " video without video_config", () ->
                        postJson(startUrl, config, fallbackProviderRequest)
                );
            }
            long providerStartResponseNanos = System.nanoTime();
            log.info(
                    "Screenplay video provider start response received runId={} scriptId={} sceneId={} provider={} model={} submissionMs={} elapsedSinceSceneStartMs={}",
                    request.runId(),
                    request.scriptId(),
                    request.sceneId(),
                    config.provider(),
                    config.model(),
                    TimeUnit.NANOSECONDS.toMillis(providerStartResponseNanos - providerSubmissionStartedNanos),
                    TimeUnit.NANOSECONDS.toMillis(providerStartResponseNanos - generationStartedNanos)
            );
            String operationId = firstText(
                    textAt(startResponse, "id"),
                    textAt(startResponse, "task_id"),
                    textAt(startResponse, "taskId"),
                    textAt(startResponse, "job_id"),
                    textAt(startResponse, "jobId"),
                    textAt(startResponse, "generation_id"),
                    textAt(startResponse, "generationId"),
                    textAt(startResponse, "name"),
                    textAt(startResponse, "operation", "name"),
                    textAt(startResponse, "data", "id"),
                    textAt(startResponse, "data", "task_id"),
                    textAt(startResponse, "file", "name"),
                    textAt(startResponse, "file_id"),
                    textAt(startResponse, "fileId"),
                    textAt(startResponse, "output_video", "name"),
                    textAt(startResponse, "output_video", "file_id"),
                    textAt(startResponse, "outputVideo", "name"),
                    textAt(startResponse, "outputVideo", "fileId"),
                    textAt(startResponse, "data", "file", "name"),
                    textAt(startResponse, "data", "file_id"),
                    textAt(startResponse, "data", "fileId"),
                    textAt(startResponse, "request_id"),
                    textAt(startResponse, "requestId"),
                    textAt(startResponse, "gateway_request_id"),
                    textAt(startResponse, "gatewayRequestId")
            );
            byte[] initialInlineVideo = firstInlineVideoBytes(startResponse);
            String initialVideoUrl = firstVideoUrl(startResponse);
            log.info(
                    "Screenplay video provider operation resolved runId={} scriptId={} sceneId={} provider={} operationIdPresent={} initialInlineVideo={} initialVideoUrl={}",
                    request.runId(),
                    request.scriptId(),
                    request.sceneId(),
                    config.provider(),
                    !operationId.isBlank(),
                    initialInlineVideo != null && initialInlineVideo.length > 0,
                    !initialVideoUrl.isBlank()
            );

            JsonNode completedResponse = startResponse;
            String videoUrl = initialVideoUrl;
            byte[] videoBytes = initialInlineVideo;
            if ((videoBytes == null || videoBytes.length == 0) && videoUrl.isBlank() && !operationId.isBlank()) {
                completedResponse = waitForCompletion(config, operationId, providerRequest, startResponse);
                videoUrl = firstVideoUrl(completedResponse);
                videoBytes = firstInlineVideoBytes(completedResponse);
            }
            if ((videoBytes == null || videoBytes.length == 0) && videoUrl.isBlank() && "gemini_omni".equals(config.provider())) {
                videoUrl = geminiOmniDownloadUrl(config, firstText(
                        textAt(completedResponse, "name"),
                        textAt(completedResponse, "file", "name"),
                        textAt(completedResponse, "output_video", "name"),
                        textAt(completedResponse, "outputVideo", "name"),
                        operationId
                ));
            }
            if ((videoBytes == null || videoBytes.length == 0) && !videoUrl.isBlank() && "gemini_omni".equals(config.provider())) {
                videoUrl = geminiOmniDownloadUrl(config, videoUrl);
            }
            long providerOperationCompletedNanos = System.nanoTime();
            log.info(
                    "Screenplay video provider output resolved runId={} scriptId={} sceneId={} provider={} operationId={} providerOperationMs={} elapsedSinceSceneStartMs={} inlineVideoBytes={} downloadUrlPresent={}",
                    request.runId(),
                    request.scriptId(),
                    request.sceneId(),
                    config.provider(),
                    operationId,
                    TimeUnit.NANOSECONDS.toMillis(providerOperationCompletedNanos - providerStartResponseNanos),
                    TimeUnit.NANOSECONDS.toMillis(providerOperationCompletedNanos - generationStartedNanos),
                    videoBytes == null ? 0 : videoBytes.length,
                    !videoUrl.isBlank()
            );
            String responseContentType = firstText(contentType(completedResponse), DEFAULT_VIDEO_MIME_TYPE);
            AssetStorageService.StoredObject streamedObject = null;
            if ((videoBytes == null || videoBytes.length == 0) && !videoUrl.isBlank()) {
                String downloadableVideoUrl = videoUrl;
                streamedObject = executeWithRetries(config, "stream " + config.provider() + " video to storage", () ->
                        downloadToCreatorAsset(
                                downloadableVideoUrl,
                                config,
                                config.provider() + " scene video",
                                request.targetObjectKey(),
                                responseContentType,
                                request.signedUrlTtl()
                        )
                );
            }
            if ((videoBytes == null || videoBytes.length == 0) && streamedObject == null) {
                Map<String, Object> responseMap = toMap(completedResponse);
                if ("dalai_llama".equals(config.provider()) && booleanValue(responseMap.get("manualApprovalRequired"), false)) {
                    throw new ManualAvatarFallbackRequiredException(
                            firstText(
                                    textValue(responseMap, "manualApprovalReason"),
                                    "Local avatar generation needs manual approval before Synthesia fallback."
                            ),
                            providerRequest,
                            responseMap
                    );
                }
                throw new IllegalStateException(config.provider() + " completed without a downloadable or inline video.");
            }
            if (videoBytes != null && videoBytes.length > 0) {
                validateVideoBytes(videoBytes, config.provider() + " inline scene video", responseContentType);
            }
            long videoTransferCompletedNanos = System.nanoTime();
            log.info(
                    "Screenplay video provider asset transfer completed runId={} scriptId={} sceneId={} provider={} streamedToStorage={} transferMs={} elapsedSinceSceneStartMs={} storedBytes={}",
                    request.runId(),
                    request.scriptId(),
                    request.sceneId(),
                    config.provider(),
                    streamedObject != null,
                    TimeUnit.NANOSECONDS.toMillis(videoTransferCompletedNanos - providerOperationCompletedNanos),
                    TimeUnit.NANOSECONDS.toMillis(videoTransferCompletedNanos - generationStartedNanos),
                    streamedObject == null ? (videoBytes == null ? 0 : videoBytes.length) : streamedObject.sizeBytes()
            );

            Map<String, Object> responseMap = toMap(completedResponse);
            Map<String, Object> costMetadata = estimateCost(
                    config,
                    request,
                    preparedRequest,
                    responseMap,
                    "PROVIDER_OR_CONFIGURED"
            );
            if (!operationId.isBlank()) {
                costMetadata.put("operationId", operationId);
                costMetadata.put("falRequestId", operationId);
            }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", config.provider());
        metadata.put("model", config.model());
        metadata.put("operationName", operationId);
        metadata.put("videoUri", videoUrl);
            metadata.put("streamedToStorage", streamedObject != null);
            if (streamedObject != null) {
                metadata.put("storageBucket", streamedObject.bucket());
                metadata.put("storageObjectKey", streamedObject.objectKey());
                metadata.put("storageSizeBytes", streamedObject.sizeBytes());
            }
            metadata.put("durationSeconds", request.durationSeconds());
            metadata.put("aspectRatio", request.aspectRatio());
            metadata.put("seed", firstValue(providerRequest.get("seed"), mapValue(providerRequest.get("parameters")).get("seed")));
            metadata.put("seriesSeed", request.seriesSeed());
            metadata.put("sceneId", request.sceneId());
            metadata.put("sceneNumber", request.sceneNumber());
            metadata.put("referenceImageUsed", providerRequestUsesReferenceImage(providerRequest));
            metadata.put("referenceImageMode", stringValue(request.providerRequest().get("referenceImageMode"), stringValue(request.providerRequest().get("storyboardReferenceMode"), "use_when_available")));
            boolean nativeAudioRequested = true;
            metadata.put("nativeAudioRequested", nativeAudioRequested);
            metadata.put("audioPolicy", "provider_audio_then_final_mix_optional");
            Map<String, Object> consistencyStrategy = new LinkedHashMap<>();
            consistencyStrategy.put("seriesSeed", firstValue(providerRequest.get("seriesSeed"), request.seriesSeed()));
            consistencyStrategy.put("sceneSeed", firstValue(providerRequest.get("seed"), mapValue(providerRequest.get("parameters")).get("seed")));
            consistencyStrategy.put("promptLocks", List.of("videoConsistencyBible", "adjacentSceneContinuity", "srtCueLock", "negativePrompt"));
            consistencyStrategy.put("referenceMode", stringValue(request.providerRequest().get("storyboardReferenceMode"), "use_when_available"));
            metadata.put("consistencyStrategy", consistencyStrategy);
            metadata.put("costMetadata", costMetadata);
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            log.info(
                    "Screenplay video provider generation finished runId={} scriptId={} sceneId={} provider={} model={} operationId={} totalElapsedMs={} costSource={}",
                    request.runId(),
                    request.scriptId(),
                    request.sceneId(),
                    config.provider(),
                    config.model(),
                    operationId,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - generationStartedNanos),
                    stringValue(costMetadata.get("pricingSource"), "")
            );
            return new GeneratedSceneVideo(
                    videoBytes,
                    streamedObject,
                    responseContentType,
                    metadata,
                    operationId,
                    sanitizeProviderPayloadForStorage(providerRequest),
                    sanitizeProviderPayloadForStorage(responseMap)
            );
        });
    }

    private GeneratedSceneVideo generateStreamedDalaiLlamaAvatarScene(
            ProviderConfig config,
            SceneVideoRequest request,
            Map<String, Object> preparedRequest,
            long generationStartedNanos
    ) {
        Map<String, Object> founderProfile = mapValue(preparedRequest.get("founderAvatarProfile"));
        Map<String, Object> localModels = mapValue(firstPresentValue(
                preparedRequest.get("localModels"),
                founderProfile.get("localModels")
        ));
        String avatarModel = firstText(
                textValue(preparedRequest, "talkingAvatarModel"),
                textValue(localModels, "talkingAvatarModel"),
                config.model(),
                "fal_happy_horse_v1_1"
        );
        String normalizedAvatarModel = normalizeProvider(avatarModel);
        boolean heygenAvatar = normalizedAvatarModel.contains("heygen")
                && (normalizedAvatarModel.contains("avatar4") || normalizedAvatarModel.contains("avatar_4"));
        boolean portraitAvatar = heygenAvatar
                || normalizedAvatarModel.contains("happy_horse")
                || normalizedAvatarModel.contains("happyhorse");
        String lipSyncModel = heygenAvatar
                ? "avatar_native"
                : firstText(textValue(preparedRequest, "lipSyncModel"), "fal_latentsync");
        Map<String, Object> sourceAsset = portraitAvatar
                ? mapValue(firstPresentValue(
                        preparedRequest.get("avatarPortraitAsset"),
                        founderProfile.get("avatarPortraitAsset")
                ))
                : mapValue(firstPresentValue(
                        preparedRequest.get("sourceAsset"),
                        founderProfile.get("sourceAsset")
                ));
        Map<String, Object> dialogueAudioAsset = mapValue(firstPresentValue(
                preparedRequest.get("dialogueAudioAsset"),
                request.providerRequest().get("dialogueAudioAsset"),
                request.scene().get("dialogueAudio"),
                preparedRequest.get("exactFounderAudioAsset")
        ));
        String sourceObjectKey = assetObjectKey(sourceAsset);
        String audioObjectKey = assetObjectKey(dialogueAudioAsset);
        if (sourceObjectKey.isBlank()) {
            throw new IllegalStateException(
                    portraitAvatar
                            ? "The approved founder portrait is missing from MinIO. Prepare and approve the avatar quality test again."
                            : "The uploaded creator video is missing from MinIO."
            );
        }
        if (audioObjectKey.isBlank()) {
            throw new IllegalStateException(
                    "The cloned scene dialogue was not stored in MinIO. Generate the approved cloned voice before avatar video."
            );
        }
        String sourceBucket = firstText(
                textValue(sourceAsset, "bucket"),
                assetStorageService.creatorAssetsBucket()
        );
        String audioBucket = firstText(
                textValue(dialogueAudioAsset, "bucket"),
                assetStorageService.creatorAssetsBucket()
        );
        String sceneFilePath = firstText(
                envString("DALAI_LLAMA_AVATAR_SCENE_FILE_PATH", ""),
                envString("DALLAI_LLAMA_AVATAR_SCENE_FILE_PATH", ""),
                DEFAULT_AVATAR_SCENE_FILE_PATH
        );
        String sceneUrl = absoluteUrl(config.baseUrl(), sceneFilePath);
        Path responsePath = null;
        try (
                AssetStorageService.StreamedObject source =
                        assetStorageService.openObjectStream(sourceBucket, sourceObjectKey);
                AssetStorageService.StreamedObject dialogueAudio =
                        assetStorageService.openObjectStream(audioBucket, audioObjectKey)
        ) {
            String sourceContentType = firstText(
                    source.contentType(),
                    textValue(sourceAsset, "contentType"),
                    portraitAvatar ? "image/jpeg" : "video/mp4"
            );
            if (portraitAvatar
                    && !sourceContentType.toLowerCase(Locale.ROOT).startsWith("image/")
                    && !sourceObjectKey.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|webp)$")) {
                throw new IllegalStateException(
                        "The portrait avatar model requires a JPG, PNG, or WebP founder portrait."
                );
            }
            MultipartBodyBuilder multipart = new MultipartBodyBuilder();
            String sourceFilename = firstText(
                    textValue(sourceAsset, "originalFilename"),
                    portraitAvatar ? "founder-portrait.jpg" : "creator-source.mp4"
            );
            multipart.part("sourceVideo", streamResource(source, sourceFilename))
                    .filename(sourceFilename)
                    .contentType(mediaType(sourceContentType, MediaType.APPLICATION_OCTET_STREAM));
            String audioFilename = firstText(
                    textValue(dialogueAudioAsset, "originalFilename"),
                    "scene-dialogue.wav"
            );
            multipart.part("dialogueAudio", streamResource(dialogueAudio, audioFilename))
                    .filename(audioFilename)
                    .contentType(mediaType(
                            firstText(dialogueAudio.contentType(), textValue(dialogueAudioAsset, "contentType"), "audio/wav"),
                            MediaType.APPLICATION_OCTET_STREAM
                    ));
            addMultipartPart(multipart, "runId", request.runId());
            addMultipartPart(multipart, "scriptId", request.scriptId());
            addMultipartPart(multipart, "sceneId", request.sceneId());
            addMultipartPart(multipart, "sceneNumber", request.sceneNumber());
            addMultipartPart(multipart, "provider", "dalai_llama");
            addMultipartPart(multipart, "model", avatarModel);
            addMultipartPart(multipart, "durationSeconds", request.durationSeconds());
            addMultipartPart(multipart, "aspectRatio", request.aspectRatio());
            addMultipartPart(multipart, "prompt", textValue(preparedRequest, "prompt"));
            addMultipartPart(multipart, "dialogueScript", textValue(preparedRequest, "dialogueScript"));
            addMultipartPart(multipart, "exactDialogue", textValue(preparedRequest, "exactDialogue"));
            addMultipartPart(multipart, "language", textValue(preparedRequest, "language"));
            addMultipartPart(multipart, "languageCode", textValue(preparedRequest, "languageCode"));
            addMultipartPart(multipart, "generationMode", request.generationMode());
            addMultipartPart(multipart, "founderAvatarProfileJson", jsonValue(founderProfile));
            addMultipartPart(multipart, "localModelsJson", jsonValue(localModels));
            addMultipartPart(multipart, "voiceModel", textValue(preparedRequest, "voiceModel"));
            addMultipartPart(multipart, "talkingAvatarModel", avatarModel);
            addMultipartPart(multipart, "imageModel", textValue(preparedRequest, "imageModel"));
            addMultipartPart(multipart, "lightingModel", textValue(preparedRequest, "lightingModel"));
            addMultipartPart(multipart, "lipSyncModel", lipSyncModel);
            addMultipartPart(multipart, "videoModel", textValue(preparedRequest, "videoModel"));
            addMultipartPart(multipart, "gpuProfile", textValue(preparedRequest, "gpuProfile"));
            addMultipartPart(
                    multipart,
                    "talkingStyle",
                    firstText(
                            textValue(preparedRequest, "talkingStyle"),
                            textValue(localModels, "talkingStyle"),
                            textValue(founderProfile, "talkingStyle"),
                            "stable"
                    )
            );
            addMultipartPart(
                    multipart,
                    "expression",
                    firstText(
                            textValue(preparedRequest, "expression"),
                            textValue(preparedRequest, "avatarExpression"),
                            textValue(founderProfile, "avatarExpression")
                    )
            );
            addMultipartPart(
                    multipart,
                    "avatarResolution",
                    firstText(
                            textValue(preparedRequest, "avatarResolution"),
                            textValue(localModels, "avatarResolution"),
                            heygenAvatar ? "720p" : "1080p"
                    )
            );
            addMultipartPart(
                    multipart,
                    "avatarCaption",
                    booleanValue(preparedRequest.get("avatarCaption"), false)
            );
            addMultipartPart(
                    multipart,
                    "avatarBackgroundJson",
                    jsonValue(firstPresentValue(
                            preparedRequest.get("avatarBackground"),
                            founderProfile.get("avatarBackground"),
                            Map.of()
                    ))
            );
            addMultipartPart(multipart, "consentConfirmed", true);
            addMultipartPart(
                    multipart,
                    "manualApprovalRequiredForFallback",
                    booleanValue(preparedRequest.get("manualApprovalRequiredForFallback"), true)
            );
            addMultipartPart(multipart, "fallbackProvider", "synthesia");
            addMultipartPart(
                    multipart,
                    "productionEnhancementEnabled",
                    booleanValue(preparedRequest.get("productionEnhancementEnabled"), false)
            );
            addMultipartPart(
                    multipart,
                    "productionEnhancementPrompt",
                    textValue(preparedRequest, "productionEnhancementPrompt")
            );

            log.info(
                    "DalaiLlama avatar scene stream handoff started runId={} scriptId={} sceneId={} avatarModel={} lipSyncModel={} portraitBytes={} dialogueAudioBytes={} targetObjectKey={}",
                    request.runId(),
                    request.scriptId(),
                    request.sceneId(),
                    avatarModel,
                    lipSyncModel,
                    source.sizeBytes(),
                    dialogueAudio.sizeBytes(),
                    request.targetObjectKey()
            );
            responsePath = Files.createTempFile("screenplay-avatar-scene-", ".mp4");
            Path streamedResponsePath = responsePath;
            WebClient.RequestBodySpec httpRequest = webClient.post()
                    .uri(sceneUrl)
                    .accept(MediaType.valueOf(DEFAULT_VIDEO_MIME_TYPE))
                    .contentType(MediaType.MULTIPART_FORM_DATA);
            addAuth(httpRequest, config);
            StreamedAvatarScene streamed = httpRequest
                    .bodyValue(multipart.build())
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(new IllegalStateException(
                                            "ai-service rejected avatar scene HTTP "
                                                    + response.statusCode().value()
                                                    + ": "
                                                    + truncate(body, 1200)
                                    )));
                        }
                        String responseContentType = response.headers().contentType()
                                .map(MediaType::toString)
                                .orElse(DEFAULT_VIDEO_MIME_TYPE);
                        Map<String, Object> providerResponse =
                                decodeAvatarProviderResponse(response.headers().asHttpHeaders());
                        return DataBufferUtils.write(
                                        response.bodyToFlux(DataBuffer.class),
                                        streamedResponsePath,
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING,
                                        StandardOpenOption.WRITE
                                )
                                .then(Mono.fromCallable(() -> {
                                    long streamedBytes = Files.size(streamedResponsePath);
                                    if (streamedBytes <= 0) {
                                        throw new IllegalStateException("ai-service returned an empty avatar scene stream.");
                                    }
                                    AssetStorageService.StoredObject stored =
                                            assetStorageService.uploadCreatorAssetFromPath(
                                                    request.targetObjectKey(),
                                                    streamedResponsePath,
                                                    responseContentType,
                                                    request.signedUrlTtl()
                                            );
                                    return new StreamedAvatarScene(
                                            stored,
                                            providerResponse,
                                            responseContentType
                                    );
                                }).subscribeOn(Schedulers.boundedElastic()));
                    })
                    .block(Duration.ofMillis(Math.max(60000, config.timeoutMs())));
            if (streamed == null) {
                throw new IllegalStateException("ai-service returned no avatar scene stream.");
            }
            Map<String, Object> providerResponse = streamed.providerResponse();
            if (booleanValue(providerResponse.get("manualApprovalRequired"), false)) {
                throw new ManualAvatarFallbackRequiredException(
                        firstText(
                                textValue(providerResponse, "manualApprovalReason"),
                                "Avatar generation needs manual approval before Synthesia fallback."
                        ),
                        preparedRequest,
                        providerResponse
                );
            }
            String operationId = firstText(
                    textValue(providerResponse, "falRequestId"),
                    textValue(providerResponse, "lipSyncRequestId"),
                    textValue(providerResponse, "avatarRequestId"),
                    textValue(providerResponse, "requestId"),
                    request.sceneId()
            );
            Map<String, Object> costMetadata = mapValue(providerResponse.get("costMetadata"));
            if (costMetadata.isEmpty()) {
                costMetadata = estimateCost(
                        config,
                        request,
                        preparedRequest,
                        providerResponse,
                        "PROVIDER_OR_CONFIGURED"
                );
            }
            costMetadata = new LinkedHashMap<>(costMetadata);
            if (!operationId.isBlank()) {
                costMetadata.putIfAbsent("operationId", operationId);
                costMetadata.putIfAbsent("falRequestId", operationId);
            }
            Map<String, Object> costUsage = new LinkedHashMap<>(mapValue(costMetadata.get("usage")));
            putIfPresent(costUsage, "falRequestId", textValue(providerResponse, "falRequestId"));
            putIfPresent(costUsage, "avatarRequestId", textValue(providerResponse, "avatarRequestId"));
            putIfPresent(costUsage, "lipSyncRequestId", textValue(providerResponse, "lipSyncRequestId"));
            costMetadata.put("usage", costUsage);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", config.provider());
            metadata.put("model", firstText(textValue(providerResponse, "model"), avatarModel));
            metadata.put("operationName", operationId);
            metadata.put("streamedToStorage", true);
            metadata.put("storageBucket", streamed.storedObject().bucket());
            metadata.put("storageObjectKey", streamed.storedObject().objectKey());
            metadata.put("storageSizeBytes", streamed.storedObject().sizeBytes());
            metadata.put("durationSeconds", request.durationSeconds());
            metadata.put("aspectRatio", request.aspectRatio());
            metadata.put("sceneId", request.sceneId());
            metadata.put("sceneNumber", request.sceneNumber());
            metadata.put("avatarModel", firstText(textValue(providerResponse, "avatarModel"), avatarModel));
            metadata.put("lipSyncModel", firstText(
                    textValue(providerResponse, "lipSyncModel"),
                    lipSyncModel
            ));
            metadata.put("mediaTransferMode", "minio_stream_multipart");
            metadata.put("nativeAudioRequested", true);
            metadata.put("audioPolicy", heygenAvatar ? "approved_clone_native_avatar" : "approved_clone_then_lip_sync");
            metadata.put("costMetadata", costMetadata);
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            log.info(
                    "DalaiLlama avatar scene stream completed runId={} scriptId={} sceneId={} operationId={} storedBytes={} totalElapsedMs={}",
                    request.runId(),
                    request.scriptId(),
                    request.sceneId(),
                    operationId,
                    streamed.storedObject().sizeBytes(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - generationStartedNanos)
            );
            return new GeneratedSceneVideo(
                    null,
                    streamed.storedObject(),
                    streamed.contentType(),
                    metadata,
                    operationId,
                    sanitizeProviderPayloadForStorage(preparedRequest),
                    sanitizeProviderPayloadForStorage(providerResponse)
            );
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not stream the approved portrait and cloned dialogue from MinIO.",
                    ex
            );
        } finally {
            if (responsePath != null) {
                try {
                    Files.deleteIfExists(responsePath);
                } catch (IOException cleanupError) {
                    log.warn(
                            "Could not remove temporary avatar response path sceneId={} path={} reason={}",
                            request.sceneId(),
                            responsePath,
                            cleanupError.getMessage()
                    );
                }
            }
        }
    }

    private ProviderConfig providerConfig(String provider, String requestedModel) {
        String normalized = normalizeProvider(provider);
        boolean omini = "omini".equals(normalized);
        boolean geminiOmni = "gemini_omni".equals(normalized);
        boolean googleVeo = "google_veo".equals(normalized);
        boolean synthesia = "synthesia".equals(normalized);
        boolean dalaiLlama = "dalai_llama".equals(normalized);
        boolean falSeedance = "seedance".equals(normalized);
        boolean googleGenerativeApi = googleVeo || geminiOmni;
        boolean googleAiStudio = googleGenerativeApi && (geminiOmni || !googleVeoUsesVertex());
        String prefix = googleVeo ? "GOOGLE_VEO" : geminiOmni ? "GEMINI_OMNI" : synthesia ? "SYNTHESIA" : dalaiLlama ? "DALAI_LLAMA" : omini ? "OMINI" : "SEEDANCE";
        String altPrefix = googleVeo ? "GOOGLE" : geminiOmni ? "GOOGLE_OMNI" : synthesia ? "SYNTHESIA_API" : dalaiLlama ? "DALLAI_LLAMA" : omini ? "OMNI" : prefix;
        String model = firstText(
                requestedModel,
                envString(prefix + "_VIDEO_MODEL", ""),
                envString(altPrefix + "_VIDEO_MODEL", ""),
                googleVeo ? "veo-3.1-generate-preview" : geminiOmni ? "gemini-omni-flash-preview" : synthesia ? "synthesia-avatar-video" : dalaiLlama ? "fal_happy_horse_v1_1" : omini ? "omini-video" : "bytedance/seedance-2.0"
        );
        if (googleVeo) {
            model = normalizeGoogleVeoModel(model);
        } else if (falSeedance) {
            model = normalizeFalSeedanceModel(model);
        }
        String baseUrl = firstText(
                envString(prefix + "_BASE_URL", ""),
                envString(altPrefix + "_BASE_URL", ""),
                googleVeo ? googleVeoBaseUrl(model, googleAiStudio)
                        : geminiOmni ? "https://generativelanguage.googleapis.com/v1beta"
                        : synthesia ? "https://api.synthesia.io"
                        : dalaiLlama ? firstText(
                                dalaiLlamaBaseUrlOverride,
                                envString("AI_SERVICE_URL", "http://ai-service.apps.svc.cluster.local:8601")
                        )
                        : omini ? "" : "https://queue.fal.run"
        );
        String defaultStartPath = googleVeo ? googleVeoStartPath(model, googleAiStudio)
                : geminiOmni ? "/interactions"
                : synthesia ? "/v2/videos"
                : dalaiLlama ? "/creator/avatar/scenes"
                : falSeedance ? "/" + falSeedanceEndpoint(model, false)
                : "/videos/generations";
        String defaultPollPath = googleVeo ? googleVeoPollPath(googleAiStudio)
                : geminiOmni ? "/files/{id}"
                : synthesia ? "/v2/videos/{id}"
                : dalaiLlama ? "/creator/avatar/jobs/{id}"
                : falSeedance ? "/" + falSeedanceEndpoint(model, false) + "/requests/{id}/status"
                : "/videos/generations/{id}";
        int defaultMaxClipSeconds = geminiOmni ? 10 : googleVeo ? 8 : dalaiLlama ? 15 : synthesia ? 20 : 15;
        BigDecimal ratePerSecond = positiveDecimal(prefix, altPrefix, "VIDEO_RATE_PER_SECOND_USD", "COST_PER_SECOND_USD");
        if (falSeedance && ratePerSecond.signum() <= 0) {
            ratePerSecond = falSeedanceRatePerSecond(model);
        }
        return new ProviderConfig(
                normalized,
                prefix,
                altPrefix,
                dalaiLlama
                        ? firstText(envString(prefix + "_AI_SERVICE_API_KEY", ""), envString(prefix + "_API_KEY", ""), envString(altPrefix + "_API_KEY", ""))
                        : falSeedance
                        ? firstText(envString("FAL_KEY", ""), envString(prefix + "_API_KEY", ""), envString(altPrefix + "_API_KEY", ""))
                        : googleGenerativeApi
                        ? firstText(
                        envString(prefix + "_API_KEY", ""),
                        envString(altPrefix + "_API_KEY", ""),
                        envString("GEMINI_API_KEY", ""),
                        envString("GOOGLE_API_KEY", ""),
                        envString("CREATOR_GEMINI_API_KEY", "")
                )
                        : firstText(envString(prefix + "_API_KEY", ""), envString(altPrefix + "_API_KEY", "")),
                baseUrl,
                firstText(envString(prefix + "_VIDEO_START_PATH", ""), envString(altPrefix + "_VIDEO_START_PATH", ""), defaultStartPath).replace("{model}", model),
                firstText(envString(prefix + "_VIDEO_POLL_PATH", ""), envString(altPrefix + "_VIDEO_POLL_PATH", ""), defaultPollPath),
                model,
                clampInt(envInt(prefix + "_MAX_CLIP_SECONDS", envInt(altPrefix + "_MAX_CLIP_SECONDS", defaultMaxClipSeconds)), 1, defaultMaxClipSeconds),
                Math.max(1, envInt(prefix + "_MAX_CONCURRENT_GENERATIONS", envInt(altPrefix + "_MAX_CONCURRENT_GENERATIONS", 1))),
                Math.max(0, envLong(prefix + "_REQUEST_MIN_INTERVAL_MS", envLong(altPrefix + "_REQUEST_MIN_INTERVAL_MS", googleGenerativeApi ? 15000 : 1000))),
                Math.max(1000, envLong(prefix + "_VIDEO_POLL_INTERVAL_MS", envLong(altPrefix + "_VIDEO_POLL_INTERVAL_MS", googleVeo ? 15000 : 5000))),
                Math.max(60000, envLong(prefix + "_VIDEO_TIMEOUT_MS", envLong(altPrefix + "_VIDEO_TIMEOUT_MS", 900000))),
                Math.max(1, envInt(prefix + "_VIDEO_MAX_ATTEMPTS", envInt(altPrefix + "_VIDEO_MAX_ATTEMPTS", geminiOmni ? 1 : 3))),
                Math.max(250, envLong(prefix + "_VIDEO_RETRY_BACKOFF_MS", envLong(altPrefix + "_VIDEO_RETRY_BACKOFF_MS", 1500))),
                firstText(envString(prefix + "_AUTH_HEADER", ""), envString(altPrefix + "_AUTH_HEADER", ""), dalaiLlama ? "Authorization" : googleAiStudio ? "x-goog-api-key" : "Authorization"),
                envString(prefix + "_AUTH_PREFIX", envString(altPrefix + "_AUTH_PREFIX", falSeedance ? "Key" : (googleAiStudio || synthesia || dalaiLlama) ? "" : "Bearer")),
                ratePerSecond,
                positiveDecimal(prefix, altPrefix, "VIDEO_RATE_PER_CLIP_USD", "COST_PER_CLIP_USD"),
                positiveDecimal(prefix, altPrefix, "VIDEO_RATE_PER_MILLION_TOKENS_USD", "COST_PER_MILLION_TOKENS_USD"),
                dalaiLlama
                        ||
                booleanEnv("SCREENPLAY_VIDEO_ALLOW_UNPRICED_PROVIDER_CALLS", false)
                        || booleanEnv(prefix + "_ALLOW_UNPRICED_PROVIDER_CALLS", booleanEnv(altPrefix + "_ALLOW_UNPRICED_PROVIDER_CALLS", false)),
                googleAiStudio
        );
    }

    private void requireProviderReady(ProviderConfig config, SceneVideoRequest request) {
        if (config.apiKey().isBlank() && !"dalai_llama".equals(config.provider())) {
            if ("google_veo".equals(config.provider()) || "gemini_omni".equals(config.provider())) {
                throw new IllegalStateException("Set " + config.prefix() + "_API_KEY, GOOGLE_API_KEY, GEMINI_API_KEY, or CREATOR_GEMINI_API_KEY for " + config.provider() + " video generation.");
            }
            throw new IllegalStateException(config.prefix() + "_API_KEY is not configured for " + config.provider() + " video generation.");
        }
        if (config.baseUrl().isBlank()) {
            if ("google_veo".equals(config.provider()) || "gemini_omni".equals(config.provider())) {
                throw new IllegalStateException("Google Veo base URL is empty. Use Gemini API defaults or set GOOGLE_CLOUD_PROJECT_ID when CREATOR_GOOGLE_GENAI_BACKEND=vertex.");
            }
            throw new IllegalStateException(config.prefix() + "_BASE_URL is not configured for " + config.provider() + " video generation.");
        }
        if (stringValue(request.prompt(), "").isBlank()) {
            throw new IllegalStateException("Scene provider prompt is required before video generation.");
        }
        if ("synthesia".equals(config.provider())) {
            String avatarId = firstText(
                    textValue(request.providerRequest(), "synthesiaAvatarId"),
                    textValue(request.providerRequest(), "avatarId"),
                    envString("SYNTHESIA_DEFAULT_AVATAR_ID", "")
            );
            String voiceId = firstText(
                    textValue(request.providerRequest(), "synthesiaVoiceId"),
                    textValue(request.providerRequest(), "voiceId"),
                    envString("SYNTHESIA_DEFAULT_VOICE_ID", "")
            );
            if (avatarId.isBlank()) {
                throw new IllegalStateException("Synthesia avatar id is required. Upload founder source, enter synthesiaAvatarId, or set SYNTHESIA_DEFAULT_AVATAR_ID.");
            }
            if (voiceId.isBlank()) {
                throw new IllegalStateException("Synthesia voice id is required. Upload founder source, enter synthesiaVoiceId, or set SYNTHESIA_DEFAULT_VOICE_ID.");
            }
        }
        if ("dalai_llama".equals(config.provider())) {
            Map<String, Object> providerRequest = request.providerRequest() == null ? Map.of() : request.providerRequest();
            Map<String, Object> founderProfile = mapValue(providerRequest.get("founderAvatarProfile"));
            Map<String, Object> localModels = mapValue(founderProfile.get("localModels"));
            String normalizedAvatarModel = normalizeProvider(firstText(
                    textValue(providerRequest, "talkingAvatarModel"),
                    textValue(localModels, "talkingAvatarModel"),
                    "fal_heygen_avatar4"
            ));
            boolean heygenAvatar = "fal_heygen_avatar4".equals(normalizedAvatarModel)
                    || "heygen_avatar4".equals(normalizedAvatarModel)
                    || "heygen_avatar_iv".equals(normalizedAvatarModel);
            if (!"APPROVED".equalsIgnoreCase(textValue(founderProfile, "voiceApprovalStatus"))) {
                throw new IllegalStateException("Approve the founder voice preview or upload exact founder audio before generating a DalaiLlama avatar scene.");
            }
            if (!heygenAvatar && !"APPROVED".equalsIgnoreCase(textValue(founderProfile, "avatarPreviewStatus"))) {
                throw new IllegalStateException("Create and approve the portrait avatar quality test before generating a DalaiLlama avatar scene.");
            }
        }
    }

    private Map<String, Object> buildProviderRequest(ProviderConfig config, SceneVideoRequest request) {
        long requestBuildStartedNanos = System.nanoTime();
        int durationSeconds = clampInt(request.durationSeconds(), 1, config.maxClipSeconds());
        long seriesSeed = request.seriesSeed() > 0 ? request.seriesSeed() : deterministicSeed(request.runId(), request.scriptId(), "series");
        long sceneSeed = request.seed() > 0 ? request.seed() : deterministicSeed(String.valueOf(seriesSeed), request.sceneId(), "scene");
        String negativePrompt = firstText(
                stringValue(request.providerRequest().get("negativePrompt"), ""),
                textValue(request.videoConsistencyBible(), "negativePrompt"),
                "no face drift, no wardrobe change, no random new actor, no changed room layout, no wrong aspect ratio, no unreadable text, no watermark, no random subtitles, no extra limbs"
        );
        String prompt = buildConsistencyPrompt(request, seriesSeed, sceneSeed, negativePrompt);
        log.info(
                "Screenplay video prompt assembled runId={} scriptId={} sceneId={} provider={} promptBuildMs={} promptChars={} srtCueCount={} previousScenePresent={} nextScenePresent={}",
                request.runId(),
                request.scriptId(),
                request.sceneId(),
                config.provider(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestBuildStartedNanos),
                prompt.length(),
                request.srtCues() == null ? 0 : request.srtCues().size(),
                request.previousScene() != null && !request.previousScene().isEmpty(),
                request.nextScene() != null && !request.nextScene().isEmpty()
        );

        if ("google_veo".equals(config.provider())) {
            return buildGoogleVeoRequest(config, request, prompt, durationSeconds, sceneSeed, negativePrompt);
        }
        if ("gemini_omni".equals(config.provider())) {
            return buildGeminiOmniRequest(config, request, prompt, durationSeconds, sceneSeed, negativePrompt);
        }
        if ("synthesia".equals(config.provider())) {
            return buildSynthesiaRequest(config, request, prompt, durationSeconds);
        }
        if ("dalai_llama".equals(config.provider())) {
            return buildDalaiLlamaAvatarRequest(config, request, prompt, durationSeconds, sceneSeed);
        }
        if ("seedance".equals(config.provider())) {
            return buildFalSeedanceRequest(config, request, prompt, durationSeconds, sceneSeed);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("prompt", truncate(prompt, envInt(config.prefix() + "_PROMPT_MAX_CHARS", 6000)));
        body.put("duration", durationSeconds);
        body.put("durationSeconds", durationSeconds);
        body.put("aspect_ratio", firstText(request.aspectRatio(), "9:16"));
        body.put("aspectRatio", firstText(request.aspectRatio(), "9:16"));
        body.put("seed", sceneSeed);
        body.put("seriesSeed", seriesSeed);
        body.put("negative_prompt", negativePrompt);
        body.put("negativePrompt", negativePrompt);
        body.put("response_format", "url");
        body.put("watermark", false);
        body.put("metadata", Map.of(
                "runId", request.runId(),
                "scriptId", request.scriptId(),
                "sceneId", request.sceneId(),
                "sceneNumber", request.sceneNumber(),
                "seriesSeed", seriesSeed,
                "sceneSeed", sceneSeed,
                "generationMode", request.generationMode()
        ));

        List<Object> referenceImages = referenceImages(request);
        if (!referenceImages.isEmpty()) {
            body.put("reference_images", referenceImages);
            body.put("referenceImages", referenceImages);
        }
        return body;
    }

    private Map<String, Object> buildFalSeedanceRequest(
            ProviderConfig config,
            SceneVideoRequest request,
            String prompt,
            int durationSeconds,
            long sceneSeed
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", falSeedanceResolution(config.model(), envString("SEEDANCE_FAL_RESOLUTION", "720p")));
        body.put("duration", String.valueOf(falSeedanceDuration(config.model(), durationSeconds)));
        body.put("aspect_ratio", falSeedanceAspectRatio(firstText(request.aspectRatio(), "9:16")));
        body.put("generate_audio", booleanValue(firstValue(
                request.providerRequest().get("generateAudio"),
                request.providerRequest().get("nativeAudioEnabled"),
                request.providerRequest().get("native_audio_enabled")
        ), booleanEnv("SEEDANCE_FAL_GENERATE_AUDIO", true)));
        body.put("bitrate_mode", falSeedanceBitrateMode(envString("SEEDANCE_FAL_BITRATE_MODE", "standard")));
        body.put("seed", sceneSeed);

        boolean productReferenceToVideo = booleanValue(
                request.providerRequest().get("seedanceReferenceToVideo"),
                false
        );
        if (productReferenceToVideo) {
            if (!supportsFalSeedanceReferenceToVideo(config.model())) {
                throw new IllegalStateException("Product CGI multi-reference generation requires Seedance 2.0.");
            }
            List<DownloadedReferenceImage> referenceImages = seedanceReferenceImages(config, request);
            if (referenceImages.size() < 2) {
                throw new IllegalStateException(
                        "Product CGI generation requires both the approved shot frame and the original product reference before calling Seedance."
                );
            }
            List<String> imageUrls = referenceImages.stream()
                    .limit(9)
                    .map(this::referenceImageDataUri)
                    .toList();
            body.put("image_urls", imageUrls);
            prompt = seedanceProductReferencePrompt(prompt, imageUrls.size());
        } else {
            DownloadedReferenceImage referenceImage = firstReferenceImage(config, request);
            if (referenceImage != null) {
                body.put("image_url", referenceImageDataUri(referenceImage));
            }
        }
        body.put("prompt", truncate(prompt, envInt("SEEDANCE_PROMPT_MAX_CHARS", 6000)));
        String endImageUrl = firstText(
                textValue(request.providerRequest(), "endImageUrl"),
                textValue(request.providerRequest(), "end_image_url")
        );
        if (!endImageUrl.isBlank()) {
            body.put("end_image_url", endImageUrl);
        }
        return body;
    }

    private String referenceImageDataUri(DownloadedReferenceImage referenceImage) {
        return "data:" + firstText(referenceImage.contentType(), DEFAULT_IMAGE_MIME_TYPE)
                + ";base64," + Base64.getEncoder().encodeToString(referenceImage.bytes());
    }

    static String seedanceProductReferencePrompt(String prompt, int imageCount) {
        StringBuilder roles = new StringBuilder(
                "@Image1 is the approved shot-specific CGI composition. Begin from its composition, camera, lighting, background, and product placement. "
                        + "@Image2 is the original canonical product reference. Preserve its exact package silhouette, geometry, logo placement, label layout, colors, materials, proportions, cap/lid details, and visible brand features in every frame. "
                        + "Use @Image2 for product identity only; do not replace @Image1's approved set or composition. "
        );
        if (imageCount > 2) {
            roles.append("@Image3 through @Image")
                    .append(imageCount)
                    .append(" are additional canonical product views used only to resolve product identity and hidden-side details. ");
        }
        roles.append("Never animate a storyboard card, drawing, contact sheet, annotation, or production label. ");
        roles.append(prompt == null ? "" : prompt.trim());
        return roles.toString().trim();
    }

    private Map<String, Object> buildSynthesiaRequest(
            ProviderConfig config,
            SceneVideoRequest request,
            String prompt,
            int durationSeconds
    ) {
        String dialogue = firstText(dialogueLockText(request), request.prompt());
        String avatarId = firstText(
                textValue(request.providerRequest(), "synthesiaAvatarId"),
                textValue(request.providerRequest(), "avatarId"),
                envString("SYNTHESIA_DEFAULT_AVATAR_ID", "")
        );
        String voiceId = firstText(
                textValue(request.providerRequest(), "synthesiaVoiceId"),
                textValue(request.providerRequest(), "voiceId"),
                envString("SYNTHESIA_DEFAULT_VOICE_ID", "")
        );
        String aspectRatio = firstText(request.aspectRatio(), "9:16");

        Map<String, Object> sceneInput = new LinkedHashMap<>();
        sceneInput.put("scriptText", truncate(dialogue, envInt("SYNTHESIA_SCRIPT_MAX_CHARS", 1800)));
        sceneInput.put("text", truncate(dialogue, envInt("SYNTHESIA_SCRIPT_MAX_CHARS", 1800)));
        sceneInput.put("avatar", avatarId);
        sceneInput.put("avatarId", avatarId);
        sceneInput.put("voice", voiceId);
        sceneInput.put("voiceId", voiceId);
        sceneInput.put("background", firstText(
                textValue(request.providerRequest(), "synthesiaBackground"),
                envString("SYNTHESIA_DEFAULT_BACKGROUND", "off_white_studio")
        ));
        sceneInput.put("language", firstText(textValue(request.providerRequest(), "languageCode"), "hi-IN"));
        sceneInput.put("durationSeconds", durationSeconds);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("test", booleanEnv("SYNTHESIA_TEST_MODE", false));
        body.put("title", "Founder avatar scene " + request.sceneNumber());
        body.put("description", truncate(prompt, 500));
        body.put("visibility", firstText(envString("SYNTHESIA_VIDEO_VISIBILITY", ""), "private"));
        body.put("aspectRatio", aspectRatio);
        body.put("input", List.of(sceneInput));
        body.put("metadata", Map.of(
                "runId", request.runId(),
                "scriptId", request.scriptId(),
                "sceneId", request.sceneId(),
                "sceneNumber", request.sceneNumber(),
                "generationMode", request.generationMode(),
                "avatarProviderMode", "synthesia"
        ));
        return body;
    }

    private Map<String, Object> buildDalaiLlamaAvatarRequest(
            ProviderConfig config,
            SceneVideoRequest request,
            String prompt,
            int durationSeconds,
            long sceneSeed
    ) {
        Map<String, Object> providerRequest = request.providerRequest() == null ? Map.of() : request.providerRequest();
        Map<String, Object> founderProfile = mapValue(providerRequest.get("founderAvatarProfile"));
        Map<String, Object> localModels = mapValue(firstValue(
                providerRequest.get("localModels"),
                providerRequest.get("localAvatarModels"),
                founderProfile.get("localModels")
        ));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", request.runId());
        body.put("scriptId", request.scriptId());
        body.put("sceneId", request.sceneId());
        body.put("sceneNumber", request.sceneNumber());
        body.put("provider", "dalai_llama");
        body.put("model", config.model());
        body.put("durationSeconds", durationSeconds);
        body.put("startSeconds", (int) longValue(firstValue(request.scene().get("startSeconds"), request.scene().get("start_seconds")), 0));
        body.put("endSeconds", (int) longValue(firstValue(request.scene().get("endSeconds"), request.scene().get("end_seconds")), durationSeconds));
        body.put("aspectRatio", firstText(request.aspectRatio(), "9:16"));
        body.put("prompt", truncate(prompt, envInt("DALAI_LLAMA_PROMPT_MAX_CHARS", 6000)));
        String captionText = dialogueLockText(request);
        body.put("dialogueScript", captionText);
        body.put("exactDialogue", captionText);
        body.put("captionText", captionText);
        body.put("spokenText", firstText(textValue(request.scene(), "spokenText"), captionText));
        body.put("pronunciationGuide", firstText(textValue(providerRequest, "pronunciationGuide"), textValue(founderProfile, "pronunciationGuide")));
        body.put("referenceTranscript", firstText(textValue(providerRequest, "referenceTranscript"), textValue(founderProfile, "referenceTranscript")));
        body.put("language", firstText(textValue(providerRequest, "language"), "Hinglish"));
        body.put("languageCode", firstText(textValue(providerRequest, "languageCode"), "hi-IN"));
        body.put("seed", sceneSeed);
        body.put("generationMode", request.generationMode());
        body.put("founderAvatarProfile", founderProfile);
        body.put("avatarId", firstText(textValue(providerRequest, "avatarId"), textValue(founderProfile, "avatarId")));
        body.put("voiceId", firstText(textValue(providerRequest, "voiceId"), textValue(founderProfile, "voiceId")));
        body.put("portraitEmbeddingId", firstText(textValue(providerRequest, "portraitEmbeddingId"), textValue(founderProfile, "portraitEmbeddingId")));
        body.put("facialFeatureEmbeddingId", firstText(textValue(providerRequest, "facialFeatureEmbeddingId"), textValue(founderProfile, "facialFeatureEmbeddingId")));
        body.put("voiceEmbeddingId", firstText(textValue(providerRequest, "voiceEmbeddingId"), textValue(founderProfile, "voiceEmbeddingId")));
        body.put("voiceProfileId", firstText(
                textValue(providerRequest, "voiceProfileId"),
                textValue(founderProfile, "voiceProfileId"),
                textValue(localModels, "voiceProfileId")
        ));
        body.put("consentConfirmed", booleanValue(firstValue(
                providerRequest.get("founderConsentConfirmed"),
                providerRequest.get("consentConfirmed"),
                founderProfile.get("consentConfirmed")
        ), false));
        Map<String, Object> sourceAsset = mapValue(founderProfile.get("sourceAsset"));
        Map<String, Object> exactAudioAsset = mapValue(firstPresentValue(
                providerRequest.get("dialogueAudioAsset"),
                request.scene().get("dialogueAudio"),
                founderProfile.get("exactFounderAudioAsset"),
                founderProfile.get("finalFounderAudioAsset")
        ));
        Map<String, Object> avatarPortraitAsset = mapValue(firstPresentValue(
                providerRequest.get("avatarPortraitAsset"),
                founderProfile.get("avatarPortraitAsset")
        ));
        String exactAudioUrl = firstText(
                textValue(providerRequest, "audioUrl"),
                textValue(providerRequest, "dialogueAudioUrl"),
                textValue(providerRequest, "finalFounderAudioUrl"),
                textValue(founderProfile, "finalFounderAudioUrl"),
                textValue(exactAudioAsset, "assetUrl"),
                textValue(exactAudioAsset, "signedUrl"),
                textValue(exactAudioAsset, "publicUrl")
        );
        body.put("sourceUrl", firstText(textValue(providerRequest, "sourceUrl"), textValue(founderProfile, "sourceUrl"), textValue(sourceAsset, "assetUrl"), textValue(sourceAsset, "signedUrl")));
        body.put("audioUrl", exactAudioUrl);
        body.put("finalFounderAudioUrl", exactAudioUrl);
        body.put("sourceAsset", mapValue(founderProfile.get("sourceAsset")));
        body.put("avatarPortraitAsset", avatarPortraitAsset);
        body.put("dialogueAudioAsset", exactAudioAsset);
        body.put("exactFounderAudioAsset", exactAudioAsset);
        body.put("localModels", localModels);
        body.put("voiceModel", firstText(textValue(localModels, "voiceModel"), "fal_minimax_voice_clone"));
        String talkingAvatarModel = firstText(
                textValue(providerRequest, "talkingAvatarModel"),
                textValue(localModels, "talkingAvatarModel"),
                "fal_heygen_avatar4"
        );
        String normalizedAvatarModel = normalizeProvider(talkingAvatarModel);
        boolean heygenAvatar = normalizedAvatarModel.contains("heygen")
                && (normalizedAvatarModel.contains("avatar4") || normalizedAvatarModel.contains("avatar_4"));
        body.put("talkingAvatarModel", talkingAvatarModel);
        body.put(
                "lipSyncModel",
                heygenAvatar
                        ? "avatar_native"
                        : firstText(
                                textValue(providerRequest, "lipSyncModel"),
                                textValue(localModels, "lipSyncModel"),
                                "fal_latentsync"
                        )
        );
        body.put("imageModel", firstText(textValue(localModels, "imageModel"), "gemini_storyboard"));
        body.put("lightingModel", firstText(textValue(localModels, "lightingModel"), "ic_lightning"));
        body.put("videoModel", firstText(textValue(localModels, "videoModel"), "fal_seedance"));
        body.put("gpuProfile", firstText(textValue(localModels, "gpuProfile"), "rtx_4060_8gb"));
        body.put("avatarResolution", firstText(
                textValue(providerRequest, "avatarResolution"),
                textValue(localModels, "avatarResolution"),
                heygenAvatar ? "720p" : "1080p"
        ));
        body.put("talkingStyle", firstText(
                textValue(providerRequest, "talkingStyle"),
                textValue(localModels, "talkingStyle"),
                textValue(founderProfile, "talkingStyle"),
                "stable"
        ));
        body.put("expression", firstText(
                textValue(providerRequest, "expression"),
                textValue(providerRequest, "avatarExpression"),
                textValue(founderProfile, "avatarExpression")
        ));
        body.put("avatarBackground", mapValue(firstPresentValue(
                providerRequest.get("avatarBackground"),
                founderProfile.get("avatarBackground")
        )));
        body.put("avatarCaption", booleanValue(providerRequest.get("avatarCaption"), false));
        body.put("avatarMotionPrompt", firstText(
                textValue(providerRequest, "avatarMotionPrompt"),
                textValue(founderProfile, "avatarMotionPrompt"),
                prompt
        ));
        body.put("productionEnhancementEnabled", booleanValue(firstValue(
                providerRequest.get("productionEnhancementEnabled"),
                founderProfile.get("productionEnhancementEnabled")
        ), false));
        body.put("productionEnhancementPrompt", firstText(
                textValue(providerRequest, "productionEnhancementPrompt"),
                textValue(founderProfile, "productionEnhancementPrompt")
        ));
        body.put("manualApprovalRequiredForFallback", booleanValue(providerRequest.get("manualApprovalRequiredForFallback"), true));
        body.put("fallbackProvider", "synthesia");
        return body;
    }

    private Map<String, Object> buildGoogleVeoRequest(
            ProviderConfig config,
            SceneVideoRequest request,
            String prompt,
            int durationSeconds,
            long sceneSeed,
            String negativePrompt
    ) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("prompt", truncate(prompt, envInt(config.prefix() + "_PROMPT_MAX_CHARS", envInt(config.altPrefix() + "_PROMPT_MAX_CHARS", 6000))));
        DownloadedReferenceImage referenceImage = firstReferenceImage(config, request);
        if (referenceImage != null) {
            instance.put("image", googleVeoImage(referenceImage));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("numberOfVideos", 1);
        parameters.put("durationSeconds", googleVeoDurationSeconds(durationSeconds));
        parameters.put("aspectRatio", firstText(request.aspectRatio(), "9:16"));
        String personGeneration = googleVeoPersonGeneration(config);
        if (!personGeneration.isBlank()) {
            parameters.put("personGeneration", personGeneration);
        }
        parameters.put("seed", sceneSeed);
        String resolution = envString("GOOGLE_VEO_RESOLUTION", envString("GOOGLE_VIDEO_RESOLUTION", ""));
        if (!resolution.isBlank()) {
            parameters.put("resolution", resolution);
        }
        String storageUri = envString("GOOGLE_VEO_OUTPUT_GCS_URI", envString("GOOGLE_OUTPUT_GCS_URI", ""));
        if (!storageUri.isBlank()) {
            parameters.put("storageUri", storageUri);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instances", List.of(instance));
        body.put("parameters", parameters);
        return body;
    }

    private Map<String, Object> googleVeoImage(DownloadedReferenceImage referenceImage) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("imageBytes", Base64.getEncoder().encodeToString(referenceImage.bytes()));
        image.put("mimeType", firstText(referenceImage.contentType(), DEFAULT_IMAGE_MIME_TYPE));
        return image;
    }

    private Map<String, Object> buildGeminiOmniRequest(
            ProviderConfig config,
            SceneVideoRequest request,
            String prompt,
            int durationSeconds,
            long sceneSeed,
            String negativePrompt
    ) {
        int omniDurationSeconds = clampInt(durationSeconds, 1, config.maxClipSeconds());
        String aspectRatio = firstText(request.aspectRatio(), "9:16");
        String omniPrompt = """
                Create a finished commercial video shot from this creative direction.

                %s

                Shot constraints:
                - Duration: %d seconds.
                - Aspect ratio: %s.
                - Keep dialogue, captions, and action aligned to the screenplay timing.
                - Preserve product packaging, character identity, wardrobe, background layout, camera language, lighting, and series seed continuity.
                - Audio mix: dialogue at a consistent level, background music ducked under speech, ambient room tone, sparse transition effects, scene-matched reverb, and smooth fades.
                - Avoid: %s
                """.formatted(prompt, omniDurationSeconds, aspectRatio, negativePrompt);

        List<Object> input = new ArrayList<>();
        DownloadedReferenceImage referenceImage = firstReferenceImage(config, request);
        if (referenceImage != null) {
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("type", "image");
            image.put("mime_type", firstText(referenceImage.contentType(), DEFAULT_IMAGE_MIME_TYPE));
            image.put("data", Base64.getEncoder().encodeToString(referenceImage.bytes()));
            input.add(image);
        }
        input.add(Map.of(
                "type", "text",
                "text", truncatePromptAtSectionBoundary(
                        omniPrompt,
                        envInt(config.prefix() + "_PROMPT_MAX_CHARS", envInt(config.altPrefix() + "_PROMPT_MAX_CHARS", 6000))
                )
        ));

        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "video");
        responseFormat.put("delivery", "uri");
        responseFormat.put("aspect_ratio", aspectRatio);

        Map<String, Object> videoConfig = new LinkedHashMap<>();
        videoConfig.put("task", referenceImage == null ? "text_to_video" : "image_to_video");

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("video_config", videoConfig);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("input", input);
        body.put("response_format", responseFormat);
        body.put("generation_config", generationConfig);
        return body;
    }

    private boolean shouldRetryGeminiOmniWithoutVideoConfig(ProviderConfig config, RuntimeException ex) {
        if (!"gemini_omni".equals(config.provider()) || ex == null) {
            return false;
        }
        String message = stringValue(ex.getMessage(), "").toLowerCase(Locale.ROOT);
        return message.contains("generation_config")
                || message.contains("video_config")
                || message.contains("unknown parameter")
                || message.contains("invalid_request");
    }

    private Map<String, Object> geminiOmniMinimalStartRequest(Map<String, Object> request) {
        Map<String, Object> fallback = new LinkedHashMap<>(request == null ? Map.of() : request);
        fallback.remove("generation_config");
        fallback.remove("generationConfig");
        Map<String, Object> responseFormat = mapValue(fallback.get("response_format"));
        responseFormat.remove("mime_type");
        responseFormat.remove("mimeType");
        if (!responseFormat.isEmpty()) {
            fallback.put("response_format", responseFormat);
        }
        return fallback;
    }

    private String googleVeoPersonGeneration(ProviderConfig config) {
        String configured = firstText(
                envString("GOOGLE_VEO_PERSON_GENERATION", ""),
                envString("GOOGLE_PERSON_GENERATION", "")
        );
        String normalized = configured
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        if (normalized.isBlank()
                || "auto".equals(normalized)
                || "default".equals(normalized)
                || "omit".equals(normalized)
                || "disabled".equals(normalized)) {
            return "";
        }
        if (config.googleAiStudio() && ("allow_adult".equals(normalized) || "allow_all".equals(normalized))) {
            log.info("Omitting unsupported Google AI Studio personGeneration={} for Veo request.", configured);
            return "";
        }
        return normalized;
    }

    private String buildConsistencyPrompt(
            SceneVideoRequest request,
            long seriesSeed,
            long sceneSeed,
            String negativePrompt
    ) {
        String globalPrompt = firstText(
                textValue(request.providerRequest(), "visualConsistencyPrompt"),
                textValue(request.providerRequest(), "consistencyLockPrompt"),
                textValue(request.seedancePromptStrategy(), "globalConsistencyPrompt")
        );
        String fastPrompt = textValue(request.seedancePromptStrategy(), "fastPacedPrompt");
        String slowPrompt = textValue(request.seedancePromptStrategy(), "slowPacedPrompt");
        String paceKey = firstText(textValue(request.videoPacingProfile(), "paceKey"), "balanced");
        String pacingPrompt = "slow_paced".equalsIgnoreCase(paceKey) ? slowPrompt : "fast_paced".equalsIgnoreCase(paceKey) ? fastPrompt : "";
        String srtCues = toJson(request.srtCues());
        String audioMixStandards = toJson(mapValue(request.providerRequest().get("audioMixStandards")));
        String imageLedAdPlan = toJson(mapValue(request.providerRequest().get("imageLedAdPlan")));
        Map<String, Object> sceneAudioProductionPlan = new LinkedHashMap<>(
                mapValue(request.providerRequest().get("audioProductionPlan"))
        );
        putIfPresent(sceneAudioProductionPlan, "sceneSoundDirection", firstPresentValue(
                request.providerRequest().get("audioDescription"),
                request.providerRequest().get("soundPrompt"),
                request.providerRequest().get("soundDesignPrompt"),
                request.scene().get("audioDescription"),
                request.scene().get("soundPrompt"),
                request.scene().get("soundDescription"),
                request.scene().get("soundDesign")
        ));
        putIfPresent(sceneAudioProductionPlan, "generateAudio", request.providerRequest().get("generateAudio"));
        String audioProductionPlan = toJson(sceneAudioProductionPlan);
        String editingPlan = toJson(mapValue(request.providerRequest().get("editingPlan")));
        String visualTreatment = toJson(mapValue(request.providerRequest().get("visualTreatment")));
        String dialogueLock = dialogueLockText(request);
        String referenceDetails = firstText(
                textValue(request.providerRequest(), "referenceImageDetails"),
                textValue(request.videoConsistencyBible(), "referenceImageDetails"),
                textValue(request.videoConsistencyBible(), "productReferenceLock")
        );
        String adjacent = toJson(Map.of(
                "previousScene", request.previousScene(),
                "nextScene", request.nextScene()
        ));
        String sceneDetailPacket = toJson(sceneDetailPacket(request));

        return """
                Generate one production-ready video scene for a larger multi-scene creator video.

                Scene prompt:
                %s

                Scene detail packet:
                %s

                Visual treatment lock:
                Apply this treatment to the final video image and movement. For slow motion, preserve smooth natural movement. For black and white or another grade, apply it to the final video only; do not render this instruction as on-screen text or interface.
                %s

                Storyboard safeguard:
                Storyboard images are planning sketches only. Never reproduce, animate, transform, or show a storyboard card, sketch, drawing, panel, or layout in the final video. Use the written scene direction for visual decisions. Only an explicitly supplied product visual anchor may be used as an image-to-video input.

                Visual consistency lock:
                Treat these as hard continuity requirements across the storyboard sequence:
                - Same character identity, face, hair length, hair color, parting, texture, volume, grooming, wardrobe, accessories, and hand/product interaction details.
                - Same background/set geography, room layout, product placement, lighting direction, color temperature, exposure, camera height, lens feel, framing rules, and movement language unless the scene explicitly changes them.
                - Do not restyle hair, change the actor, move the environment, swap product packaging, or introduce new props/logos.
                %s

                Pacing:
                %s
                %s

                Adjacent scene continuity:
                %s

                Dialogue delivery lock:
                If this provider generates native speech/audio, speak the full dialogue below word-for-word in order. Do not paraphrase, skip words, summarize, invent new lines, or cut off the final words. If native speech is not supported, keep the visual mouth movement and timing compatible with the same complete line for the later voice mix.
                %s

                Caption and SRT lock:
                Use only these caption/voice cues when text or speech is visible. Do not invent random subtitles. Caption text must match the exact dialogue when captions are shown.
                %s

                Audio mix standards:
                When this provider generates or preserves audio, keep dialogue at a consistent speech-first level, duck background music under speech, maintain scene-matched room tone, use small whooshes/clicks/transitions sparingly, match reverb to the physical space, and use smooth fades between segments.
                %s

                Image-led ad plan:
                If a reference image is attached, treat it as the product/first-frame visual anchor. Preserve product identity, texture, packaging, lighting direction, camera geometry, and color. Animate only the requested motion, such as slow-motion falling, pouring, macro texture, or multi-direction product movement.
                Reference details:
                %s
                %s

                Voice, music, and sound plan:
                Generate or preserve native dialogue/voice when supported. Keep speech intelligible, music under dialogue, room tone consistent, sparse product foley/SFX, and scene-matched reverb.
                %s

                Editor handoff plan:
                %s

                Seed strategy:
                Series seed %d keeps the whole video family consistent. Scene seed %d is deterministic for this scene.

                Negative prompt:
                %s
                """.formatted(
                request.prompt(),
                sceneDetailPacket,
                visualTreatment,
                firstText(globalPrompt, toJson(request.videoConsistencyBible())),
                toJson(request.videoPacingProfile()),
                pacingPrompt,
                adjacent,
                dialogueLock,
                srtCues,
                audioMixStandards,
                referenceDetails,
                imageLedAdPlan,
                audioProductionPlan,
                editingPlan,
                seriesSeed,
                sceneSeed,
                negativePrompt
        ).trim();
    }

    private String dialogueLockText(SceneVideoRequest request) {
        String explicit = firstText(
                textValue(request.providerRequest(), "exactDialogue"),
                textValue(request.providerRequest(), "dialogueScript"),
                textValue(request.scene(), "dialogueScript"),
                textValue(request.scene(), "voiceover"),
                textValue(request.scene(), "voiceOver"),
                textValue(request.scene(), "spokenLine")
        );
        if (!explicit.isBlank()) {
            return explicit;
        }
        List<String> lines = new ArrayList<>();
        if (request.srtCues() != null) {
            for (Object cue : request.srtCues()) {
                String text = firstText(
                        textValue(mapValue(cue), "text"),
                        textValue(mapValue(cue), "line"),
                        textValue(mapValue(cue), "caption")
                );
                if (!text.isBlank()) {
                    lines.add(text);
                }
            }
        }
        return String.join(" ", lines).replaceAll("\\s+", " ").trim();
    }

    private Map<String, Object> sceneDetailPacket(SceneVideoRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        putIfPresent(details, "hook", firstPresentValue(
                request.providerRequest().get("hook"),
                request.scene().get("hook"),
                request.scene().get("openingHook"),
                request.scene().get("hookLine"),
                request.scene().get("narrativeBeat"),
                request.scene().get("beatTitle"),
                request.scene().get("title")
        ));
        putIfPresent(details, "retentionGoal", firstPresentValue(
                request.providerRequest().get("retentionGoal"),
                request.scene().get("retentionGoal"),
                request.scene().get("retention_goal")
        ));
        putIfPresent(details, "patternInterrupt", firstPresentValue(
                request.providerRequest().get("patternInterrupt"),
                request.scene().get("patternInterrupt"),
                request.scene().get("pattern_interrupt")
        ));
        putIfPresent(details, "productStoryBeat", firstPresentValue(
                request.providerRequest().get("productStoryBeat"),
                request.scene().get("productStoryBeat"),
                request.scene().get("narrativeBeat"),
                request.scene().get("purpose")
        ));
        putIfPresent(details, "productCreativeEvidence", firstPresentValue(
                request.providerRequest().get("productCreativeEvidence"),
                request.scene().get("productCreativeEvidence")
        ));
        putIfPresent(details, "productShotPlan", firstPresentValue(
                request.providerRequest().get("productShotPlan"),
                request.scene().get("productShotPlan")
        ));
        putIfPresent(details, "sceneDetail", firstPresentValue(
                request.providerRequest().get("sceneDetail"),
                request.scene().get("sceneDetail"),
                request.scene().get("sceneDetails"),
                request.scene().get("description"),
                request.scene().get("action"),
                request.scene().get("visualPrompt")
        ));
        putIfPresent(details, "backgroundDetail", firstPresentValue(
                request.providerRequest().get("backgroundDetail"),
                request.scene().get("backgroundDetail"),
                request.scene().get("background"),
                request.scene().get("setting"),
                request.scene().get("location"),
                request.scene().get("environment"),
                request.scene().get("setDescription")
        ));
        putIfPresent(details, "characterDetail", firstPresentValue(
                request.providerRequest().get("characterDetail"),
                request.scene().get("characterDetail"),
                request.scene().get("characterDetails"),
                request.scene().get("character"),
                request.scene().get("characters"),
                request.videoConsistencyBible().get("storyCharacters"),
                request.videoConsistencyBible().get("characters")
        ));
        putIfPresent(details, "wardrobe", request.scene().get("wardrobe"));
        putIfPresent(details, "props", request.scene().get("props"));
        putIfPresent(details, "lighting", request.scene().get("lighting"));
        putIfPresent(details, "camera", firstValue(request.scene().get("camera"), request.scene().get("shotType")));
        putIfPresent(details, "visualTreatment", firstPresentValue(
                request.providerRequest().get("visualTreatment"),
                request.scene().get("visualTreatment")
        ));
        putIfPresent(details, "cinematicExecution", firstPresentValue(
                request.providerRequest().get("cinematicExecution"),
                request.scene().get("cinematicExecution")
        ));
        putIfPresent(details, "editingNotes", firstPresentValue(
                request.providerRequest().get("editingNotes"),
                request.scene().get("editingNotes")
        ));
        putIfPresent(details, "creatorDirection", firstPresentValue(
                request.providerRequest().get("creatorDirection"),
                request.scene().get("creatorDirection")
        ));
        putIfPresent(details, "brollStyle", request.scene().get("brollStyle"));
        putIfPresent(details, "captionStyle", request.scene().get("captionStyle"));
        putIfPresent(details, "audioDescription", firstPresentValue(
                request.providerRequest().get("audioDescription"),
                request.providerRequest().get("soundPrompt"),
                request.providerRequest().get("soundDesignPrompt"),
                request.scene().get("audioDescription"),
                request.scene().get("soundDescription"),
                request.scene().get("soundDesign")
        ));
        putIfPresent(details, "generateAudio", request.providerRequest().get("generateAudio"));
        putIfPresent(details, "adFormat", firstPresentValue(
                request.providerRequest().get("adFormat"),
                request.scene().get("adFormat")
        ));
        putIfPresent(details, "formatStructure", firstPresentValue(
                request.scene().get("formatStructure"),
                request.providerRequest().get("formatStructure")
        ));
        putIfPresent(details, "formatPlaybook", firstPresentValue(
                request.providerRequest().get("formatPlaybook"),
                request.scene().get("formatPlaybook")
        ));
        putIfPresent(details, "generationMode", request.generationMode());
        putIfPresent(details, "referenceImageMode", request.providerRequest().get("referenceImageMode"));
        putIfPresent(details, "referenceImageUrlPresent", !referenceImageUrlCandidates(request).isEmpty());
        return details;
    }

    private Map<String, Object> estimateCost(
            ProviderConfig config,
            SceneVideoRequest request,
            Map<String, Object> providerRequest,
            Map<String, Object> providerResponse,
            String source
    ) {
        boolean modelApiInteracted = providerResponse != null && !providerResponse.isEmpty()
                && !"CONFIGURED_PREFLIGHT".equalsIgnoreCase(source);
        Map<String, Object> responseUsage = mapValue(firstValue(
                providerResponse.get("usage"),
                mapValue(providerResponse.get("data")).get("usage"),
                mapValue(providerResponse.get("billing")).get("usage")
        ));
        BigDecimal responseCost = firstPositive(
                decimalValue(providerResponse.get("cost")),
                decimalValue(providerResponse.get("total_cost")),
                decimalValue(providerResponse.get("totalCost")),
                decimalValue(mapValue(providerResponse.get("billing")).get("total_cost")),
                decimalValue(mapValue(providerResponse.get("billing")).get("totalCost")),
                decimalValue(responseUsage.get("cost")),
                BigDecimal.ZERO
        );
        long reportedTokens = Math.max(0, longValue(firstValue(
                responseUsage.get("billable_tokens"),
                responseUsage.get("billableTokens"),
                responseUsage.get("video_tokens"),
                responseUsage.get("videoTokens"),
                responseUsage.get("total_tokens"),
                responseUsage.get("totalTokens")
        ), 0));
        BigDecimal tokenCost = BigDecimal.ZERO;
        if (reportedTokens > 0 && config.ratePerMillionTokensUsd().signum() > 0) {
            tokenCost = BigDecimal.valueOf(reportedTokens)
                    .divide(ONE_MILLION, 8, RoundingMode.HALF_UP)
                    .multiply(config.ratePerMillionTokensUsd())
                    .setScale(6, RoundingMode.HALF_UP);
        }
        BigDecimal seconds = BigDecimal.valueOf(Math.max(1, request.durationSeconds()));
        BigDecimal effectiveRatePerSecond = config.ratePerSecondUsd();
        if ("seedance".equals(config.provider())
                && positiveDecimal(config.prefix(), config.altPrefix(), "VIDEO_RATE_PER_SECOND_USD", "COST_PER_SECOND_USD").signum() <= 0) {
            effectiveRatePerSecond = falSeedanceRatePerSecond(
                    config.model(),
                    providerRequestUsesReferenceImage(providerRequest)
            );
        }
        BigDecimal secondCost = effectiveRatePerSecond.signum() > 0
                ? seconds.multiply(effectiveRatePerSecond).setScale(6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal clipCost = config.ratePerClipUsd().signum() > 0
                ? config.ratePerClipUsd().setScale(6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal total = firstPositive(responseCost, tokenCost, secondCost, clipCost, BigDecimal.ZERO);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("durationSeconds", seconds);
        usage.put("billableSeconds", seconds);
        usage.put("clipCount", 1);
        usage.put("sceneId", request.sceneId());
        usage.put("sceneNumber", request.sceneNumber());
        usage.put("aspectRatio", request.aspectRatio());
        usage.put("reportedTokens", reportedTokens);
        usage.put("providerUsage", responseUsage);

        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("provider", config.provider());
        cost.put("model", config.model());
        cost.put("operation", "screenplay_video_scene_generation");
        cost.put("currency", "USD");
        cost.put("rateUnit", "SECOND");
        cost.put("ratePerSecond", effectiveRatePerSecond);
        cost.put("ratePerClip", config.ratePerClipUsd());
        cost.put("ratePerMillionTokens", config.ratePerMillionTokensUsd());
        cost.put("totalCost", total);
        cost.put("actualTotalCost", total);
        cost.put("usage", usage);
        cost.put("estimated", responseCost.signum() <= 0);
        cost.put("pricingSource", source);
        cost.put("modelApiInteracted", modelApiInteracted);
        cost.put("debitTrigger", modelApiInteracted ? "PROVIDER_RESPONSE_COMPLETED" : "PREFLIGHT_ESTIMATE");
        cost.put("generatedAt", OffsetDateTime.now().toString());
        if (responseCost.signum() > 0) {
            cost.put("providerReportedCost", responseCost);
        }
        if (tokenCost.signum() > 0) {
            cost.put("tokenCost", tokenCost);
        }
        if (secondCost.signum() > 0) {
            cost.put("durationCost", secondCost);
        }
        if (clipCost.signum() > 0) {
            cost.put("clipCost", clipCost);
        }
        return cost;
    }

    private JsonNode waitForCompletion(
            ProviderConfig config,
            String operationId,
            Map<String, Object> providerRequest,
            JsonNode startResponse
    ) {
        boolean falSeedance = "seedance".equals(config.provider());
        String pollUrl = falSeedance
                ? firstText(
                        textAt(startResponse, "status_url"),
                        textAt(startResponse, "statusUrl"),
                        falSeedanceRequestUrl(config, providerRequest, operationId, "status")
                )
                : "gemini_omni".equals(config.provider())
                ? geminiOmniPollUrl(config, operationId)
                : absoluteUrl(config.baseUrl(), config.pollPath().replace("{id}", operationId));
        String falResponseUrl = falSeedance
                ? firstText(
                        textAt(startResponse, "response_url"),
                        textAt(startResponse, "responseUrl"),
                        falSeedanceRequestUrl(config, providerRequest, operationId, "response")
                )
                : "";
        long pollingStartedNanos = System.nanoTime();
        int pollAttempts = 0;
        Instant deadline = Instant.now().plusMillis(config.timeoutMs());
        String lastStatus = "";
        log.info("Screenplay video provider polling started provider={} operationId={} pollIntervalMs={} timeoutMs={}",
                config.provider(), operationId, config.pollIntervalMs(), config.timeoutMs());
        while (Instant.now().isBefore(deadline)) {
            pollAttempts++;
            JsonNode response = executeWithRetries(config, "poll " + config.provider() + " video", () -> {
                if ("google_veo".equals(config.provider())) {
                    return config.googleAiStudio()
                            ? getJson(pollUrl, config)
                            : postJson(pollUrl, config, Map.of("operationName", operationId));
                }
                return getJson(pollUrl, config);
            });
            String status = firstText(
                    textAt(response, "status"),
                    textAt(response, "state"),
                    textAt(response, "file", "state"),
                    textAt(response, "file", "status"),
                    textAt(response, "metadata", "state"),
                    textAt(response, "task_status"),
                    textAt(response, "data", "status"),
                    textAt(response, "data", "state")
            ).toLowerCase(Locale.ROOT);
            if (!status.equals(lastStatus)) {
                log.info("{} video status operationId={} status={} pollAttempt={} elapsedMs={}",
                        config.provider(),
                        operationId,
                        status,
                        pollAttempts,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pollingStartedNanos));
                lastStatus = status;
            }
            boolean done = response.path("done").asBoolean(false);
            if (done && !response.path("error").isObject()) {
                log.info("Screenplay video provider polling completed provider={} operationId={} pollAttempts={} elapsedMs={}",
                        config.provider(), operationId, pollAttempts,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pollingStartedNanos));
                return response;
            }
            if (isSucceeded(status) || (!firstVideoUrl(response).isBlank() && status.isBlank())) {
                String providerError = firstText(
                        textAt(response, "error", "message"),
                        textAt(response, "error"),
                        textAt(response, "message")
                );
                if (falSeedance && !providerError.isBlank()) {
                    throw new IllegalStateException("DalaiLlama Video generation failed: " + providerError);
                }
                if (falSeedance) {
                    String responseUrl = firstText(
                            textAt(response, "response_url"),
                            textAt(response, "responseUrl"),
                            falResponseUrl
                    );
                    if (responseUrl.isBlank()) {
                        throw new IllegalStateException("DalaiLlama Video completed without a response URL.");
                    }
                    JsonNode result = executeWithRetries(config, "fetch DalaiLlama Video result", () -> getJson(responseUrl, config));
                    if (!firstText(textAt(result, "error", "message"), textAt(result, "error")).isBlank()) {
                        throw new IllegalStateException("DalaiLlama Video result contained an error: "
                                + firstText(textAt(result, "error", "message"), textAt(result, "error")));
                    }
                    return result;
                }
                log.info("Screenplay video provider polling completed provider={} operationId={} pollAttempts={} elapsedMs={}",
                        config.provider(), operationId, pollAttempts,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pollingStartedNanos));
                return response;
            }
            if (isFailed(status) || response.path("error").isObject()) {
                throw new IllegalStateException(config.provider() + " video generation failed status=" + status
                        + " error=" + firstText(textAt(response, "error", "message"), textAt(response, "message")));
            }
            sleep(config.pollIntervalMs(), config.provider() + " video generation");
        }
        throw new IllegalStateException(config.provider() + " video generation timed out after " + config.timeoutMs()
                + " ms after " + pollAttempts + " polls.");
    }

    private String providerStartUrl(ProviderConfig config, Map<String, Object> providerRequest) {
        if ("seedance".equals(config.provider())) {
            return absoluteUrl(config.baseUrl(), "/" + falSeedanceEndpointForRequest(config.model(), providerRequest));
        }
        return absoluteUrl(config.baseUrl(), config.startPath());
    }

    private String falSeedanceRequestUrl(
            ProviderConfig config,
            Map<String, Object> providerRequest,
            String operationId,
            String action
    ) {
        String endpoint = falSeedanceEndpointForRequest(config.model(), providerRequest);
        return absoluteUrl(config.baseUrl(), "/" + endpoint + "/requests/" + operationId + "/" + action);
    }

    private String falSeedanceEndpointForRequest(String model, Map<String, Object> providerRequest) {
        boolean referenceToVideo = providerRequest != null
                && providerRequest.get("image_urls") instanceof Collection<?> imageUrls
                && !imageUrls.isEmpty();
        boolean imageToVideo = providerRequest != null
                && !stringValue(providerRequest.get("image_url"), "").isBlank();
        return falSeedanceEndpoint(model, imageToVideo, referenceToVideo);
    }

    private JsonNode postJson(String url, ProviderConfig config, Map<String, Object> body) {
        WebClient.RequestBodySpec request = webClient.post()
                .uri(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        addAuth(request, config);
        addFalHeaders(request, config);
        return request
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(Math.max(60000, config.timeoutMs())));
    }

    private JsonNode getJson(String url, ProviderConfig config) {
        WebClient.RequestHeadersSpec<?> request = webClient.get()
                .uri(url)
                .header("Accept", "application/json");
        addAuth(request, config);
        addFalHeaders(request, config);
        return request
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(Math.max(60000, Math.min(config.timeoutMs(), 180000))));
    }

    private byte[] downloadBytes(String url, ProviderConfig config, String label) {
        if (url != null && url.startsWith("gs://")) {
            throw new IllegalStateException(label + " was returned as a Google Cloud Storage URI. Leave GOOGLE_VEO_OUTPUT_GCS_URI empty for inline video bytes, or add a GCS transfer adapter.");
        }
        WebClient.RequestHeadersSpec<?> request = webClient.get()
                .uri(URI.create(url));
        if (config.googleAiStudio()) {
            addAuth(request, config);
        }
        ResponseEntity<byte[]> response = request
                .retrieve()
                .toEntity(byte[].class)
                .block(Duration.ofMinutes(3));
        byte[] bytes = response == null ? null : response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException(label + " was empty.");
        }
        String contentType = response == null ? "" : stringValue(response.getHeaders().getFirst("Content-Type"), "");
        validateVideoBytes(bytes, label, contentType);
        return bytes;
    }

    private AssetStorageService.StoredObject downloadToCreatorAsset(
            String url,
            ProviderConfig config,
            String label,
            String objectKey,
            String fallbackContentType,
            Duration signedUrlTtl
    ) {
        if (url != null && url.startsWith("gs://")) {
            throw new IllegalStateException(label + " was returned as a Google Cloud Storage URI. Leave GOOGLE_VEO_OUTPUT_GCS_URI empty or add a GCS transfer adapter.");
        }
        String safeObjectKey = firstText(objectKey);
        if (safeObjectKey.isBlank()) {
            throw new IllegalStateException(label + " target storage object key is missing.");
        }
        Duration requestTimeout = Duration.ofMillis(Math.max(60000, Math.min(config.timeoutMs(), 600000)));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(requestTimeout)
                .header("Accept", "video/*,application/octet-stream,*/*");
        if (config.googleAiStudio()) {
            addAuth(request, config);
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = readSmallBody(response.body());
                throw new IllegalStateException(label + " download failed HTTP " + response.statusCode() + ". body=" + body);
            }
            String responseContentType = firstText(
                    response.headers().firstValue("content-type").orElse(""),
                    fallbackContentType,
                    DEFAULT_VIDEO_MIME_TYPE
            );
            try (InputStream responseBody = response.body()) {
                HeaderCaptureInputStream capturedBody = new HeaderCaptureInputStream(responseBody, 512);
                AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAssetFromStream(
                        safeObjectKey,
                        capturedBody,
                        responseContentType,
                        signedUrlTtl == null ? Duration.ofDays(7) : signedUrlTtl
                );
                try {
                    validateVideoStream(stored.sizeBytes(), capturedBody.capturedBytes(), label, stored.contentType());
                } catch (RuntimeException validationFailure) {
                    assetStorageService.deleteCreatorObject(stored.objectKey());
                    throw validationFailure;
                }
                return stored;
            }
        } catch (IOException ex) {
            throw new IllegalStateException(label + " stream failed.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " stream was interrupted.", ex);
        }
    }

    private InputStreamResource streamResource(
            AssetStorageService.StreamedObject object,
            String filename
    ) {
        return new InputStreamResource(object.inputStream()) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public long contentLength() {
                return object.sizeBytes();
            }
        };
    }

    private MediaType mediaType(String value, MediaType fallback) {
        try {
            return MediaType.parseMediaType(firstText(value, fallback.toString()));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void addMultipartPart(MultipartBodyBuilder multipart, String name, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            multipart.part(name, text);
        }
    }

    private String jsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialize avatar scene metadata.", ex);
        }
    }

    private Map<String, Object> decodeAvatarProviderResponse(HttpHeaders headers) {
        String encoded = firstText(headers == null ? null : headers.getFirst(AVATAR_METADATA_HEADER));
        if (encoded.isBlank()) {
            return new LinkedHashMap<>(Map.of(
                    "status", "COMPLETED",
                    "provider", "dalai_llama"
            ));
        }
        try {
            byte[] decoded;
            try {
                decoded = Base64.getUrlDecoder().decode(encoded);
            } catch (IllegalArgumentException ignored) {
                decoded = Base64.getDecoder().decode(encoded);
            }
            Map<String, Object> response = objectMapper.readValue(
                    decoded,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            return response == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response);
        } catch (Exception ex) {
            throw new IllegalStateException("ai-service returned invalid avatar scene metadata.", ex);
        }
    }

    private void addAuth(WebClient.RequestHeadersSpec<?> request, ProviderConfig config) {
        if (config.apiKey().isBlank() || config.authHeader().isBlank()) {
            return;
        }
        request.header(config.authHeader(), authHeaderValue(config));
    }

    private void addFalHeaders(WebClient.RequestHeadersSpec<?> request, ProviderConfig config) {
        if (!"seedance".equals(config.provider())) {
            return;
        }
        request.header("X-Fal-Store-IO", booleanEnv("SEEDANCE_FAL_STORE_IO", false) ? "1" : "0");
    }

    private void addAuth(HttpRequest.Builder request, ProviderConfig config) {
        if (config.apiKey().isBlank() || config.authHeader().isBlank()) {
            return;
        }
        request.header(config.authHeader(), authHeaderValue(config));
    }

    private String authHeaderValue(ProviderConfig config) {
        String authPrefix = config.authPrefix();
        return authPrefix == null || authPrefix.isBlank()
                ? config.apiKey()
                : authPrefix.trim() + " " + config.apiKey();
    }

    private <T> T withProviderLease(ProviderConfig config, Supplier<T> supplier) {
        Semaphore semaphore = providerSemaphores.computeIfAbsent(
                config.provider(),
                ignored -> new Semaphore(config.maxConcurrentGenerations())
        );
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(config.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IllegalStateException(config.provider() + " video queue timed out waiting for a provider slot.");
            }
            return supplier.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + config.provider() + " provider slot.", ex);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private <T> T executeProviderStartWithRetries(ProviderConfig config, String label, Supplier<T> supplier) {
        return executeWithRetries(config, label, supplier, true);
    }

    private <T> T executeWithRetries(ProviderConfig config, String label, Supplier<T> supplier) {
        return executeWithRetries(config, label, supplier, false);
    }

    private <T> T executeWithRetries(
            ProviderConfig config,
            String label,
            Supplier<T> supplier,
            boolean rateLimitPaidSubmission
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            if (rateLimitPaidSubmission) {
                waitForLocalRateLimit(config, label);
            }
            long attemptStartedNanos = System.nanoTime();
            try {
                T result = supplier.get();
                if (rateLimitPaidSubmission) {
                    log.info("Screenplay video provider submission accepted provider={} label={} attempt={}/{} elapsedMs={}",
                            config.provider(),
                            label,
                            attempt,
                            config.maxAttempts(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attemptStartedNanos));
                }
                return result;
            } catch (WebClientResponseException ex) {
                String responseBody = ex.getResponseBodyAsString();
                lastFailure = new IllegalStateException("%s failed HTTP %s. body=%s"
                        .formatted(label, ex.getStatusCode(), truncate(responseBody, 2500)), ex);
                if (rateLimitPaidSubmission) {
                    log.warn("Screenplay video provider submission rejected provider={} label={} attempt={}/{} status={} elapsedMs={}",
                            config.provider(),
                            label,
                            attempt,
                            config.maxAttempts(),
                            ex.getStatusCode(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attemptStartedNanos));
                }
                if (providerCreditsDepleted(responseBody)) {
                    log.error("Screenplay video provider credits depleted provider={} label={}; stopping without a duplicate submission.",
                            config.provider(), label);
                    throw lastFailure;
                }
                if (!retryable(ex.getStatusCode()) || attempt >= config.maxAttempts()) {
                    throw lastFailure;
                }
                log.warn("Retrying screenplay video provider request provider={} label={} attempt={}/{} status={}",
                        config.provider(), label, attempt + 1, config.maxAttempts(), ex.getStatusCode());
                sleep(retryDelayMs(config, ex, attempt), label);
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt >= config.maxAttempts()) {
                    throw ex;
                }
                sleep(retryDelayMs(config, null, attempt), label);
            }
        }
        throw lastFailure == null ? new IllegalStateException(label + " failed.") : lastFailure;
    }

    private void waitForLocalRateLimit(ProviderConfig config, String label) {
        long minIntervalMs = config.requestMinIntervalMs();
        if (minIntervalMs <= 0) {
            return;
        }
        AtomicLong nextAllowed = providerNextRequestAtMs.computeIfAbsent(config.provider(), ignored -> new AtomicLong(0));
        while (true) {
            long now = System.currentTimeMillis();
            long previous = nextAllowed.get();
            long scheduled = Math.max(now, previous);
            if (nextAllowed.compareAndSet(previous, scheduled + minIntervalMs)) {
                long waitMs = scheduled - now;
                if (waitMs > 0) {
                    log.info("Screenplay video provider submission delayed provider={} label={} waitMs={} minIntervalMs={}",
                            config.provider(), label, waitMs, minIntervalMs);
                    sleep(waitMs, config.provider() + " local rate limit");
                }
                return;
            }
        }
    }

    private long retryDelayMs(ProviderConfig config, WebClientResponseException ex, int failedAttempt) {
        if (ex != null) {
            String retryAfter = ex.getHeaders().getFirst("Retry-After");
            if (retryAfter != null && !retryAfter.isBlank()) {
                try {
                    return Math.max(1000, Long.parseLong(retryAfter.trim()) * 1000L);
                } catch (NumberFormatException ignored) {
                    // Fall through to exponential backoff.
                }
            }
        }
        long base = config.retryBackoffMs();
        return Math.min(60000, base * (1L << Math.min(5, Math.max(0, failedAttempt - 1))));
    }

    private boolean retryable(HttpStatusCode statusCode) {
        int status = statusCode == null ? 0 : statusCode.value();
        return status == 408 || status == 409 || status == 425 || status == 429 || status >= 500;
    }

    private boolean providerCreditsDepleted(String responseBody) {
        String normalized = stringValue(responseBody, "").toLowerCase(Locale.ROOT);
        return normalized.contains("prepayment credits are depleted")
                || normalized.contains("credits are depleted")
                || normalized.contains("insufficient credits")
                || normalized.contains("billing account") && normalized.contains("disabled");
    }

    private String firstVideoUrl(JsonNode node) {
        return firstText(
                typedVideoText(node, "uri"),
                typedVideoText(node, "url"),
                typedVideoText(node, "download_uri"),
                typedVideoText(node, "downloadUri"),
                textAt(node, "video_url"),
                textAt(node, "videoUrl"),
                textAt(node, "url"),
                textAt(node, "uri"),
                textAt(node, "downloadUri"),
                textAt(node, "download_url"),
                textAt(node, "download"),
                textAt(node, "downloadUrl"),
                textAt(node, "output", "0"),
                textAt(node, "output", "0", "url"),
                textAt(node, "output", "0", "uri"),
                textAt(node, "outputs", "0", "url"),
                textAt(node, "outputs", "0", "uri"),
                textAt(node, "data", "video_url"),
                textAt(node, "data", "videoUrl"),
                textAt(node, "data", "url"),
                textAt(node, "data", "uri"),
                textAt(node, "data", "downloadUri"),
                textAt(node, "data", "downloadUrl"),
                textAt(node, "data", "download"),
                textAt(node, "data", "output", "0"),
                textAt(node, "data", "output", "0", "url"),
                textAt(node, "data", "output", "0", "uri"),
                textAt(node, "result", "video_url"),
                textAt(node, "result", "videoUrl"),
                textAt(node, "result", "url"),
                textAt(node, "result", "uri"),
                textAt(node, "result", "downloadUrl"),
                textAt(node, "result", "download"),
                textAt(node, "video", "url"),
                textAt(node, "video", "uri"),
                textAt(node, "video", "downloadUrl"),
                textAt(node, "output_video", "uri"),
                textAt(node, "output_video", "url"),
                textAt(node, "outputVideo", "uri"),
                textAt(node, "outputVideo", "url"),
                textAt(node, "data", "output_video", "uri"),
                textAt(node, "data", "outputVideo", "uri"),
                textAt(node, "result", "output_video", "uri"),
                textAt(node, "result", "outputVideo", "uri"),
                textAt(node, "steps", "0", "output", "0", "uri"),
                textAt(node, "steps", "0", "content", "0", "uri"),
                textAt(node, "steps", "0", "model_output", "0", "uri"),
                textAt(node, "steps", "0", "modelOutput", "0", "uri"),
                textAt(node, "videos", "0", "gcsUri"),
                textAt(node, "response", "videos", "0", "gcsUri"),
                textAt(node, "response", "generateVideoResponse", "generatedSamples", "0", "video", "uri"),
                textAt(node, "generatedVideos", "0", "video", "uri"),
                textAt(node, "response", "generatedVideos", "0", "video", "uri"),
                textAt(node, "response", "generateVideoResponse", "generatedVideos", "0", "video", "uri"),
                textAt(node, "assets", "video"),
                textAt(node, "candidates", "0", "content", "parts", "0", "file_data", "file_uri"),
                textAt(node, "candidates", "0", "content", "parts", "0", "fileData", "fileUri"),
                textAt(node, "candidates", "0", "content", "parts", "0", "video", "uri"),
                textAt(node, "candidates", "0", "content", "parts", "0", "video", "url"),
                textAt(node, "candidates", "0", "content", "parts", "1", "file_data", "file_uri"),
                textAt(node, "candidates", "0", "content", "parts", "1", "fileData", "fileUri"),
                textAt(node, "candidates", "0", "content", "parts", "1", "video", "uri"),
                textAt(node, "candidates", "0", "content", "parts", "1", "video", "url")
        );
    }

    private byte[] firstInlineVideoBytes(JsonNode node) {
        String encoded = firstText(
                typedVideoText(node, "data"),
                textAt(node, "b64_json"),
                textAt(node, "video", "b64_json"),
                textAt(node, "data", "0", "b64_json"),
                textAt(node, "data", "video", "b64_json"),
                textAt(node, "result", "b64_json"),
                textAt(node, "bytesBase64Encoded"),
                textAt(node, "video", "bytesBase64Encoded"),
                textAt(node, "video", "data"),
                textAt(node, "output_video", "data"),
                textAt(node, "outputVideo", "data"),
                textAt(node, "data", "output_video", "data"),
                textAt(node, "data", "outputVideo", "data"),
                textAt(node, "result", "output_video", "data"),
                textAt(node, "result", "outputVideo", "data"),
                textAt(node, "steps", "0", "content", "0", "data"),
                textAt(node, "steps", "0", "output", "0", "data"),
                textAt(node, "steps", "0", "model_output", "0", "data"),
                textAt(node, "steps", "0", "modelOutput", "0", "data"),
                textAt(node, "videos", "0", "bytesBase64Encoded"),
                textAt(node, "response", "videos", "0", "bytesBase64Encoded"),
                textAt(node, "response", "generateVideoResponse", "generatedSamples", "0", "video", "bytesBase64Encoded"),
                textAt(node, "generatedVideos", "0", "video", "bytesBase64Encoded"),
                textAt(node, "response", "generatedVideos", "0", "video", "bytesBase64Encoded"),
                textAt(node, "response", "generateVideoResponse", "generatedVideos", "0", "video", "bytesBase64Encoded"),
                textAt(node, "candidates", "0", "content", "parts", "0", "inline_data", "data"),
                textAt(node, "candidates", "0", "content", "parts", "0", "inlineData", "data"),
                textAt(node, "candidates", "0", "content", "parts", "1", "inline_data", "data"),
                textAt(node, "candidates", "0", "content", "parts", "1", "inlineData", "data")
        );
        if (encoded.isBlank()) {
            return null;
        }
        return Base64.getDecoder().decode(encoded);
    }

    private String contentType(JsonNode node) {
        return firstText(
                typedVideoText(node, "mime_type"),
                typedVideoText(node, "mimeType"),
                textAt(node, "content_type"),
                textAt(node, "contentType"),
                textAt(node, "mime_type"),
                textAt(node, "mimeType"),
                textAt(node, "video", "mimeType"),
                textAt(node, "video", "mime_type"),
                textAt(node, "output_video", "mime_type"),
                textAt(node, "output_video", "mimeType"),
                textAt(node, "outputVideo", "mime_type"),
                textAt(node, "outputVideo", "mimeType"),
                textAt(node, "videos", "0", "mimeType"),
                textAt(node, "response", "videos", "0", "mimeType"),
                textAt(node, "response", "generateVideoResponse", "generatedSamples", "0", "video", "mimeType"),
                textAt(node, "generatedVideos", "0", "video", "mimeType"),
                textAt(node, "response", "generatedVideos", "0", "video", "mimeType"),
                textAt(node, "response", "generateVideoResponse", "generatedVideos", "0", "video", "mimeType"),
                textAt(node, "candidates", "0", "content", "parts", "0", "inline_data", "mime_type"),
                textAt(node, "candidates", "0", "content", "parts", "0", "inlineData", "mimeType"),
                textAt(node, "candidates", "0", "content", "parts", "0", "file_data", "mime_type"),
                textAt(node, "candidates", "0", "content", "parts", "0", "fileData", "mimeType"),
                textAt(node, "candidates", "0", "content", "parts", "1", "inline_data", "mime_type"),
                textAt(node, "candidates", "0", "content", "parts", "1", "inlineData", "mimeType"),
                textAt(node, "candidates", "0", "content", "parts", "1", "file_data", "mime_type"),
                textAt(node, "candidates", "0", "content", "parts", "1", "fileData", "mimeType"),
                DEFAULT_VIDEO_MIME_TYPE
        );
    }

    /**
     * Interactions responses keep the generated video in a model-output step, not
     * at a fixed array index. Looking up only steps[0] accidentally inspected the
     * original user input and caused completed Omni videos to be treated as empty.
     */
    private String typedVideoText(JsonNode node, String field) {
        JsonNode video = firstTypedVideoNode(node);
        return video == null ? "" : textAt(video, field);
    }

    private JsonNode firstTypedVideoNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject() && "video".equalsIgnoreCase(node.path("type").asText())) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode video = firstTypedVideoNode(child);
            if (video != null) {
                return video;
            }
        }
        return null;
    }

    private List<Object> referenceImages(SceneVideoRequest request) {
        List<Object> references = new ArrayList<>();
        if (!shouldUseReferenceImage(request)) {
            return references;
        }
        for (String url : referenceImageUrlCandidates(request)) {
            references.add(Map.of("url", url));
        }
        return references;
    }

    private List<DownloadedReferenceImage> seedanceReferenceImages(
            ProviderConfig config,
            SceneVideoRequest request
    ) {
        List<DownloadedReferenceImage> images = new ArrayList<>();
        List<Integer> fingerprints = new ArrayList<>();
        List<Map<String, Object>> generatedAssets = new ArrayList<>();
        addProductReferenceImageAsset(generatedAssets, request.providerRequest().get("generatedProductImageAssets"));
        List<String> generatedUrls = new ArrayList<>();
        addReferenceImageUrl(generatedUrls, request.providerRequest().get("generatedProductImageUrls"));
        addReferenceImageUrl(generatedUrls, request.providerRequest().get("generatedProductImageUrl"));
        List<Map<String, Object>> canonicalAssets = new ArrayList<>();
        addProductReferenceImageAsset(canonicalAssets, request.providerRequest().get("canonicalProductImageAssets"));
        List<String> canonicalUrls = new ArrayList<>();
        addReferenceImageUrl(canonicalUrls, request.providerRequest().get("canonicalProductImageUrls"));
        List<Object> candidates = new ArrayList<>();
        candidates.addAll(generatedAssets);
        candidates.addAll(generatedUrls);
        candidates.addAll(canonicalAssets);
        candidates.addAll(canonicalUrls);
        candidates.addAll(seedanceReferenceImageAssetCandidates(request));
        candidates.addAll(seedanceReferenceImageUrlCandidates(request));
        RuntimeException lastFailure = null;
        for (Object candidate : candidates) {
            try {
                DownloadedReferenceImage image = candidate instanceof Map<?, ?> map
                        ? downloadReferenceImageAsset(mapValue(map), config)
                        : downloadReferenceImage(stringValue(candidate, ""), config);
                int fingerprint = java.util.Arrays.hashCode(image.bytes());
                if (!fingerprints.contains(fingerprint)) {
                    images.add(image);
                    fingerprints.add(fingerprint);
                }
            } catch (RuntimeException ex) {
                lastFailure = ex;
                log.warn(
                        "Could not load Seedance product reference provider={} sceneId={} source={} errorType={} errorMessage={}",
                        config.provider(),
                        request.sceneId(),
                        candidate instanceof Map<?, ?> map
                                ? redactedAssetSource(mapValue(map))
                                : redactUrl(stringValue(candidate, "")),
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
            }
            if (images.size() >= 9) {
                break;
            }
        }
        if (images.size() < 2 && lastFailure != null) {
            throw new IllegalStateException(
                    "Could not load both the generated product frame and canonical product reference for Seedance.",
                    lastFailure
            );
        }
        return images;
    }

    private List<Map<String, Object>> seedanceReferenceImageAssetCandidates(SceneVideoRequest request) {
        List<Map<String, Object>> assets = new ArrayList<>();
        addProductReferenceImageAsset(assets, request.providerRequest().get("seedanceReferenceImageAssets"));
        addProductReferenceImageAsset(assets, request.providerRequest().get("generatedProductImageAssets"));
        addProductReferenceImageAsset(assets, request.providerRequest().get("canonicalProductImageAssets"));
        return assets.stream().limit(9).toList();
    }

    private List<String> seedanceReferenceImageUrlCandidates(SceneVideoRequest request) {
        List<String> urls = new ArrayList<>();
        addReferenceImageUrl(urls, request.providerRequest().get("seedanceReferenceImageUrls"));
        addReferenceImageUrl(urls, request.providerRequest().get("generatedProductImageUrls"));
        addReferenceImageUrl(urls, request.providerRequest().get("generatedProductImageUrl"));
        addReferenceImageUrl(urls, request.providerRequest().get("canonicalProductImageUrls"));
        return urls.stream().limit(9).toList();
    }

    private DownloadedReferenceImage firstReferenceImage(ProviderConfig config, SceneVideoRequest request) {
        long referenceResolutionStartedNanos = System.nanoTime();
        if (!shouldUseReferenceImage(request)) {
            log.info("Screenplay video reference image skipped provider={} sceneId={} mode={}",
                    config.provider(), request.sceneId(), referenceImageMode(request));
            return null;
        }
        boolean required = requiresReferenceImage(request);
        RuntimeException lastFailure = null;
        List<Map<String, Object>> assetCandidates = referenceImageAssetCandidates(request);
        List<String> urls = referenceImageUrlCandidates(request);
        log.info("Screenplay video reference image resolution started provider={} sceneId={} required={} assetCandidates={} urlCandidates={}",
                config.provider(), request.sceneId(), required, assetCandidates.size(), urls.size());
        for (Map<String, Object> asset : assetCandidates) {
            long candidateStartedNanos = System.nanoTime();
            try {
                DownloadedReferenceImage image = downloadReferenceImageAsset(asset, config);
                log.info(
                        "Screenplay video reference image loaded provider={} sceneId={} source={} contentType={} bytes={} candidateLoadMs={} resolutionElapsedMs={}",
                        config.provider(),
                        request.sceneId(),
                        image.redactedSource(),
                        image.contentType(),
                        image.bytes().length,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - candidateStartedNanos),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - referenceResolutionStartedNanos)
                );
                return image;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                log.warn("Could not load stored reference image provider={} sceneId={} source={} candidateLoadMs={} errorType={} errorMessage={}",
                        config.provider(), request.sceneId(), redactedAssetSource(asset),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - candidateStartedNanos),
                        ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        if (assetCandidates.isEmpty() && urls.isEmpty()) {
            if (required) {
                throw new IllegalStateException("This scene requires a generated image anchor, but no storyboard/product image asset or URL was present.");
            }
            log.info("Screenplay video reference image not available provider={} sceneId={} resolutionElapsedMs={}",
                    config.provider(), request.sceneId(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - referenceResolutionStartedNanos));
            return null;
        }
        for (String url : urls) {
            long candidateStartedNanos = System.nanoTime();
            try {
                DownloadedReferenceImage image = downloadReferenceImage(url, config);
                log.info(
                        "Screenplay video reference image loaded provider={} sceneId={} source={} contentType={} bytes={} candidateLoadMs={} resolutionElapsedMs={}",
                        config.provider(),
                        request.sceneId(),
                        image.redactedSource(),
                        image.contentType(),
                        image.bytes().length,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - candidateStartedNanos),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - referenceResolutionStartedNanos)
                );
                return image;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                log.warn("Could not load reference image provider={} sceneId={} role=image_anchor candidateLoadMs={} errorType={} errorMessage={}",
                        config.provider(), request.sceneId(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - candidateStartedNanos),
                        ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        if (required && lastFailure != null) {
            throw new IllegalStateException("This scene requires a generated image anchor, but every candidate image failed to load.", lastFailure);
        }
        return null;
    }

    private boolean shouldUseReferenceImage(SceneVideoRequest request) {
        String mode = referenceImageMode(request);
        if (mode.equals("ignore") || mode.equals("none") || mode.equals("off")) {
            return false;
        }
        if ((mode.equals("prompt_only") || mode.contains("storyboard")) && !requiresReferenceImage(request)) {
            return false;
        }
        return requiresReferenceImage(request) || hasProductImageReference(request);
    }

    private boolean requiresReferenceImage(SceneVideoRequest request) {
        Map<String, Object> imageLedAdPlan = mapValue(request.providerRequest().get("imageLedAdPlan"));
        return booleanValue(firstValue(
                request.providerRequest().get("requireReferenceImage"),
                request.providerRequest().get("requireImageAnchors"),
                imageLedAdPlan.get("requireImageAnchors")
        ), false)
                || (booleanValue(imageLedAdPlan.get("enabled"), false) && hasProductImageReference(request));
    }

    private String referenceImageMode(SceneVideoRequest request) {
        return firstText(
                stringValue(request.providerRequest().get("referenceImageMode"), ""),
                stringValue(request.providerRequest().get("storyboardReferenceMode"), ""),
                "use_when_available"
        ).toLowerCase(Locale.ROOT);
    }

    private boolean hasProductImageReference(SceneVideoRequest request) {
        return !referenceImageAssetCandidates(request).isEmpty() || !referenceImageUrlCandidates(request).isEmpty();
    }

    private List<Map<String, Object>> referenceImageAssetCandidates(SceneVideoRequest request) {
        List<Map<String, Object>> assets = new ArrayList<>();
        addProductReferenceImageAsset(assets, request.providerRequest().get("productImageAssets"));
        addProductReferenceImageAsset(assets, request.providerRequest().get("generatedProductImageAssets"));
        addProductReferenceImageAsset(assets, request.providerRequest().get("referenceImageAssets"));
        addProductReferenceImageAsset(assets, request.providerRequest().get("referenceAssets"));
        addProductReferenceImageAsset(assets, request.scene().get("productImageAssets"));
        addProductReferenceImageAsset(assets, request.scene().get("generatedProductImageAssets"));
        return assets;
    }

    private void addProductReferenceImageAsset(List<Map<String, Object>> assets, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addProductReferenceImageAsset(assets, item));
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, Object> map = mapValue(rawMap);
        String objectKey = assetObjectKey(map);
        if (!objectKey.isBlank() && isProductVisualAnchor(map)) {
            String bucket = assetBucket(map);
            if (assets.stream().noneMatch(existing -> assetBucket(existing).equals(bucket) && assetObjectKey(existing).equals(objectKey))) {
                assets.add(map);
            }
            return;
        }
        addProductReferenceImageAsset(assets, map.get("productImage"));
        addProductReferenceImageAsset(assets, map.get("productAsset"));
        addProductReferenceImageAsset(assets, map.get("generatedProductImage"));
        addProductReferenceImageAsset(assets, map.get("productImageAssets"));
        addProductReferenceImageAsset(assets, map.get("generatedProductImageAssets"));
    }

    private String assetBucket(Map<String, Object> asset) {
        return firstText(
                stringValue(asset.get("bucket"), ""),
                stringValue(asset.get("assetBucket"), ""),
                stringValue(asset.get("storageBucket"), ""),
                assetStorageService.creatorAssetsBucket()
        );
    }

    private String assetObjectKey(Map<String, Object> asset) {
        return firstText(
                stringValue(asset.get("objectKey"), ""),
                stringValue(asset.get("object_key"), ""),
                stringValue(asset.get("key"), ""),
                stringValue(asset.get("storageKey"), "")
        );
    }

    private List<String> referenceImageUrlCandidates(SceneVideoRequest request) {
        List<String> urls = new ArrayList<>();
        addReferenceImageUrl(urls, request.providerRequest().get("referenceImageUrls"));
        addReferenceImageUrl(urls, request.providerRequest().get("referenceImageUrl"));
        addReferenceImageUrl(urls, request.scene().get("productImageUrl"));
        addReferenceImageUrl(urls, request.scene().get("generatedProductImageUrl"));
        return urls;
    }

    private void addReferenceImageUrl(List<String> urls, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addReferenceImageUrl(urls, item));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (!isProductVisualAnchor(mapValue(map))) {
                return;
            }
            addReferenceImageUrl(urls, map.get("productImageUrl"));
            addReferenceImageUrl(urls, map.get("generatedProductImageUrl"));
            addReferenceImageUrl(urls, map.get("publicUrl"));
            addReferenceImageUrl(urls, map.get("signedUrl"));
            addReferenceImageUrl(urls, map.get("assetUrl"));
            addReferenceImageUrl(urls, map.get("url"));
            addReferenceImageUrl(urls, map.get("href"));
            return;
        }
        String url = stringValue(value, "").trim();
        if (url.isBlank() || isVideoLikeUrl(url) || urls.contains(url)) {
            return;
        }
        urls.add(url);
    }

    private boolean isProductVisualAnchor(Map<String, Object> asset) {
        String role = firstText(
                stringValue(asset.get("referenceRole"), ""),
                stringValue(asset.get("reference_role"), ""),
                stringValue(asset.get("assetRole"), ""),
                stringValue(asset.get("role"), ""),
                stringValue(asset.get("assetType"), ""),
                stringValue(asset.get("assetKind"), ""),
                stringValue(asset.get("kind"), "")
        ).toLowerCase(Locale.ROOT);
        return role.contains("product") || role.contains("packshot") || role.contains("catalog") || role.contains("sku");
    }

    private DownloadedReferenceImage downloadReferenceImageAsset(Map<String, Object> asset, ProviderConfig config) {
        String bucket = assetBucket(asset);
        String objectKey = assetObjectKey(asset);
        if (bucket.isBlank() || objectKey.isBlank()) {
            throw new IllegalStateException("Reference image asset is missing bucket or object key.");
        }
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            assetStorageService.downloadObjectToOutputStream(bucket, objectKey, outputStream);
            byte[] bytes = outputStream.toByteArray();
            int maxBytes = Math.max(256 * 1024, envInt(config.prefix() + "_REFERENCE_IMAGE_MAX_BYTES", 16 * 1024 * 1024));
            String contentType = firstText(
                    stringValue(asset.get("contentType"), ""),
                    stringValue(asset.get("mimeType"), ""),
                    inferImageContentType(objectKey),
                    DEFAULT_IMAGE_MIME_TYPE
            );
            validateReferenceImageBytes(bytes, maxBytes, contentType, bucket + "/" + objectKey);
            return new DownloadedReferenceImage(bytes, contentType, redactStorageSource(bucket, objectKey), "image_anchor");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load reference image asset " + redactStorageSource(bucket, objectKey) + ".", ex);
        }
    }

    private DownloadedReferenceImage downloadReferenceImage(String url, ProviderConfig config) {
        ResponseEntity<byte[]> response = webClient.get()
                .uri(URI.create(url))
                .header("Accept", "image/*,*/*")
                .retrieve()
                .toEntity(byte[].class)
                .block(Duration.ofSeconds(Math.max(15, envInt(config.prefix() + "_REFERENCE_IMAGE_DOWNLOAD_TIMEOUT_SECONDS", 45))));
        byte[] bytes = response == null ? null : response.getBody();
        int maxBytes = Math.max(256 * 1024, envInt(config.prefix() + "_REFERENCE_IMAGE_MAX_BYTES", 16 * 1024 * 1024));
        String contentType = firstText(
                response == null ? "" : response.getHeaders().getFirst("Content-Type"),
                inferImageContentType(url),
                DEFAULT_IMAGE_MIME_TYPE
        );
        validateReferenceImageBytes(bytes, maxBytes, contentType, url);
        return new DownloadedReferenceImage(bytes, contentType, redactUrl(url), "image_anchor");
    }

    private void validateVideoBytes(byte[] bytes, String label, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException(label + " was empty.");
        }
        int minBytes = Math.max(512, envInt("CREATOR_VIDEO_MIN_VALID_BYTES", envInt("GOOGLE_VEO_MIN_VIDEO_BYTES", 1024)));
        if (bytes.length < minBytes) {
            throw new IllegalStateException(label + " download was too small to be a rendered video bytes=" + bytes.length
                    + " body=" + asciiSnippet(bytes));
        }
        String normalizedContentType = stringValue(contentType, "").toLowerCase(Locale.ROOT);
        if (isNonMediaContentType(normalizedContentType) || isTextErrorPayload(bytes)) {
            throw new IllegalStateException(label + " download returned a non-video payload contentType="
                    + firstText(contentType, "unknown") + " body=" + asciiSnippet(bytes));
        }
        if (!looksLikeVideo(bytes)) {
            throw new IllegalStateException(label + " download did not contain a recognized video file header contentType="
                    + firstText(contentType, "unknown") + " bytes=" + bytes.length);
        }
    }

    private void validateVideoStream(Long sizeBytes, byte[] capturedHeader, String label, String contentType) {
        long safeSizeBytes = sizeBytes == null ? 0 : sizeBytes;
        if (safeSizeBytes <= 0) {
            throw new IllegalStateException(label + " stream was empty.");
        }
        int minBytes = Math.max(512, envInt("CREATOR_VIDEO_MIN_VALID_BYTES", envInt("GOOGLE_VEO_MIN_VIDEO_BYTES", 1024)));
        if (safeSizeBytes < minBytes) {
            throw new IllegalStateException(label + " stream was too small to be a rendered video bytes=" + safeSizeBytes
                    + " body=" + asciiSnippet(capturedHeader));
        }
        String normalizedContentType = stringValue(contentType, "").toLowerCase(Locale.ROOT);
        if (isNonMediaContentType(normalizedContentType) || isTextErrorPayload(capturedHeader)) {
            throw new IllegalStateException(label + " stream returned a non-video payload contentType="
                    + firstText(contentType, "unknown") + " body=" + asciiSnippet(capturedHeader));
        }
        if (!looksLikeVideo(capturedHeader)) {
            throw new IllegalStateException(label + " stream did not contain a recognized video file header contentType="
                    + firstText(contentType, "unknown") + " bytes=" + safeSizeBytes);
        }
    }

    private void validateReferenceImageBytes(byte[] bytes, int maxBytes, String contentType, String source) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Reference image was empty.");
        }
        if (bytes.length > maxBytes) {
            throw new IllegalStateException("Reference image is too large for inline video generation bytes=" + bytes.length + " maxBytes=" + maxBytes);
        }
        String normalizedContentType = stringValue(contentType, "").toLowerCase(Locale.ROOT);
        if (isNonMediaContentType(normalizedContentType) || isTextErrorPayload(bytes)) {
            throw new IllegalStateException("Reference image download returned a non-image payload source="
                    + redactUrl(source) + " contentType=" + firstText(contentType, "unknown") + " body=" + asciiSnippet(bytes));
        }
        if (!looksLikeImage(bytes) && !normalizedContentType.startsWith("image/")) {
            throw new IllegalStateException("Reference image download did not contain a recognized image file header source="
                    + redactUrl(source) + " contentType=" + firstText(contentType, "unknown") + " bytes=" + bytes.length);
        }
    }

    private boolean isNonMediaContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("application/json")
                || normalized.contains("text/")
                || normalized.contains("text-html")
                || normalized.contains("application/xml")
                || normalized.contains("html");
    }

    private boolean looksLikeVideo(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        if ((bytes[0] & 0xFF) == 0x1A && (bytes[1] & 0xFF) == 0x45 && (bytes[2] & 0xFF) == 0xDF && (bytes[3] & 0xFF) == 0xA3) {
            return true;
        }
        int limit = Math.min(bytes.length - 3, 64);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 'f' && bytes[i + 1] == 't' && bytes[i + 2] == 'y' && bytes[i + 3] == 'p') {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeImage(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        return ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF)
                || ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')
                || (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F')
                || (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P');
    }

    private boolean isTextErrorPayload(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        int index = 0;
        while (index < bytes.length && Character.isWhitespace((char) bytes[index])) {
            index++;
        }
        if (index >= bytes.length) {
            return false;
        }
        byte first = bytes[index];
        if (first == '{' || first == '[' || first == '<') {
            return true;
        }
        int limit = Math.min(bytes.length, 256);
        int printable = 0;
        for (int i = 0; i < limit; i++) {
            int value = bytes[i] & 0xFF;
            if (value == 0) {
                return false;
            }
            if (value == '\n' || value == '\r' || value == '\t' || (value >= 32 && value < 127)) {
                printable++;
            }
        }
        if (printable < limit * 0.85) {
            return false;
        }
        String text = new String(bytes, 0, limit, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return text.contains("\"error\"") || text.contains("error") || text.contains("\"status\"");
    }

    private String asciiSnippet(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String value = new String(bytes, 0, Math.min(bytes.length, 220), StandardCharsets.UTF_8)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
        return truncate(value, 220);
    }

    private String readSmallBody(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        try (InputStream body = inputStream) {
            return asciiSnippet(body.readNBytes(4096));
        } catch (IOException ex) {
            return "unreadable response body: " + ex.getMessage();
        }
    }

    private boolean providerRequestUsesReferenceImage(Map<String, Object> providerRequest) {
        Object instancesValue = providerRequest == null ? null : providerRequest.get("instances");
        if (instancesValue instanceof List<?> instances && !instances.isEmpty()) {
            Map<String, Object> instance = mapValue(instances.get(0));
            if (!mapValue(instance.get("image")).isEmpty()) {
                return true;
            }
        }
        Object inputValue = providerRequest == null ? null : providerRequest.get("input");
        if (inputValue instanceof List<?> input) {
            for (Object item : input) {
                Map<String, Object> itemMap = mapValue(item);
                String type = stringValue(itemMap.get("type"), "");
                if ("image".equalsIgnoreCase(type) || !stringValue(itemMap.get("mime_type"), "").isBlank()) {
                    return true;
                }
            }
        }
        Object contentsValue = providerRequest == null ? null : providerRequest.get("contents");
        if (contentsValue instanceof List<?> contents) {
            for (Object content : contents) {
                Object partsValue = mapValue(content).get("parts");
                if (partsValue instanceof List<?> parts) {
                    for (Object part : parts) {
                        Map<String, Object> partMap = mapValue(part);
                        if (!mapValue(partMap.get("inline_data")).isEmpty()
                                || !mapValue(partMap.get("inlineData")).isEmpty()
                                || !mapValue(partMap.get("file_data")).isEmpty()
                                || !mapValue(partMap.get("fileData")).isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        return providerRequest != null
                && (!stringValue(providerRequest.get("image_url"), "").isBlank()
                || providerRequest.get("image_urls") instanceof Collection<?> imageUrls && !imageUrls.isEmpty()
                || !mapValue(providerRequest.get("first_frame")).isEmpty()
                || !mapValue(providerRequest.get("image")).isEmpty()
                || providerRequest.containsKey("reference_images")
                || providerRequest.containsKey("referenceImages"));
    }

    private String inferImageContentType(String url) {
        String lower = stringValue(url, "").toLowerCase(Locale.ROOT);
        int queryIndex = lower.indexOf('?');
        if (queryIndex >= 0) {
            lower = lower.substring(0, queryIndex);
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        return DEFAULT_IMAGE_MIME_TYPE;
    }

    private boolean isVideoLikeUrl(String value) {
        String lower = stringValue(value, "").toLowerCase(Locale.ROOT);
        return lower.contains(".mp4") || lower.contains(".mov") || lower.contains(".webm") || lower.contains("video/");
    }

    private String redactUrl(String url) {
        String value = stringValue(url, "");
        int queryIndex = value.indexOf('?');
        return queryIndex >= 0 ? value.substring(0, queryIndex) + "?..." : value;
    }

    private String redactedAssetSource(Map<String, Object> asset) {
        return redactStorageSource(assetBucket(asset), assetObjectKey(asset));
    }

    private String redactStorageSource(String bucket, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return stringValue(bucket, "");
        }
        int keep = Math.min(56, objectKey.length());
        String tail = objectKey.substring(objectKey.length() - keep);
        return firstText(bucket, "bucket") + "/..." + tail;
    }

    private String absoluteUrl(String baseUrl, String path) {
        if (path == null || path.isBlank()) {
            return trimTrailingSlash(baseUrl);
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        if (path.startsWith(":")) {
            return trimTrailingSlash(baseUrl) + path;
        }
        return trimTrailingSlash(baseUrl) + "/" + trimLeadingSlash(path);
    }

    private String geminiOmniPollUrl(ProviderConfig config, String operationId) {
        if (operationId != null && operationId.startsWith("http")) {
            return operationId;
        }
        return absoluteUrl(config.baseUrl(), geminiOmniFileName(operationId));
    }

    private String geminiOmniDownloadUrl(ProviderConfig config, String fileOrUrl) {
        String value = firstText(fileOrUrl);
        if (value.isBlank()) {
            return "";
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            if (value.contains("generativelanguage.googleapis.com") && value.contains("/files/") && !value.contains(":download")) {
                return trimTrailingSlash(value) + ":download?alt=media";
            }
            return value;
        }
        return absoluteUrl(config.baseUrl(), geminiOmniFileName(value) + ":download?alt=media");
    }

    private String geminiOmniFileName(String value) {
        String normalized = firstText(value);
        if (normalized.isBlank()) {
            return "";
        }
        normalized = trimLeadingSlash(normalized);
        if (normalized.startsWith("v1beta/")) {
            normalized = normalized.substring("v1beta/".length());
        }
        if (normalized.startsWith("files/")) {
            return normalized;
        }
        return "files/" + normalized;
    }

    private boolean isSucceeded(String status) {
        String value = status == null ? "" : status.toLowerCase(Locale.ROOT);
        return value.equals("succeeded") || value.equals("succeed") || value.equals("success")
                || value.equals("completed") || value.equals("complete") || value.equals("done")
                || value.equals("active") || value.equals("ready");
    }

    private boolean isFailed(String status) {
        String value = status == null ? "" : status.toLowerCase(Locale.ROOT);
        return value.equals("failed") || value.equals("error") || value.equals("canceled")
                || value.equals("cancelled") || value.equals("rejected");
    }

    private long deterministicSeed(String... parts) {
        int hash = Objects.hash((Object[]) parts);
        long positive = Integer.toUnsignedLong(hash);
        return positive == 0 ? 1 : positive;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
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

    private String providerPromptForLog(Map<String, Object> providerRequest) {
        String prompt = stringValue(providerRequest == null ? null : providerRequest.get("prompt"), "");
        if (!prompt.isBlank()) {
            return prompt;
        }
        Object instancesValue = providerRequest == null ? null : providerRequest.get("instances");
        if (instancesValue instanceof List<?> instances && !instances.isEmpty()) {
            return stringValue(mapValue(instances.get(0)).get("prompt"), "");
        }
        Object contentsValue = providerRequest == null ? null : providerRequest.get("contents");
        if (contentsValue instanceof List<?> contents) {
            for (Object content : contents) {
                Object partsValue = mapValue(content).get("parts");
                if (partsValue instanceof List<?> parts) {
                    for (Object part : parts) {
                        String text = stringValue(mapValue(part).get("text"), "");
                        if (!text.isBlank()) {
                            return text;
                        }
                    }
                }
            }
        }
        return "";
    }

    private String providerPayloadForLog(Map<String, Object> providerRequest) {
        int maxChars = Math.max(1000, envInt("SCREENPLAY_VIDEO_PROVIDER_PAYLOAD_LOG_MAX_CHARS", 12000));
        return truncate(toJson(sanitizeProviderPayload(providerRequest, "")), maxChars);
    }

    private Map<String, Object> sanitizeProviderPayloadForStorage(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return mapValue(sanitizeProviderStorageValue(payload, ""));
    }

    private Object sanitizeProviderStorageValue(Object value, String key) {
        String normalizedKey = stringValue(key, "")
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
        if (normalizedKey.contains("authorization")
                || normalizedKey.contains("apikey")
                || normalizedKey.contains("secret")
                || normalizedKey.equals("accesstoken")
                || normalizedKey.equals("refreshtoken")
                || normalizedKey.equals("idtoken")) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            rawMap.forEach((rawKey, rawValue) -> {
                String childKey = stringValue(rawKey, "");
                sanitized.put(childKey, sanitizeProviderStorageValue(rawValue, childKey));
            });
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : collection) {
                sanitized.add(sanitizeProviderStorageValue(item, key));
            }
            return sanitized;
        }
        if (value instanceof byte[] bytes) {
            return "[binary bytes=" + bytes.length + " stored_in_object_storage]";
        }
        if (value instanceof String text) {
            if (isLikelyBase64PayloadKey(normalizedKey) && text.length() > 80) {
                return "[base64 chars=" + text.length() + " omitted_stored_in_object_storage]";
            }
            int dataUrlMarker = text.indexOf(";base64,");
            if (dataUrlMarker > 0 && text.length() > dataUrlMarker + 80) {
                return "[data-url chars=" + text.length() + " omitted_stored_in_object_storage]";
            }
            if (text.length() > 40_000) {
                return truncate(text, 40_000);
            }
        }
        return value;
    }

    private Object sanitizeProviderPayload(Object value, String key) {
        String normalizedKey = stringValue(key, "").toLowerCase(Locale.ROOT);
        if (normalizedKey.contains("authorization")
                || normalizedKey.contains("api_key")
                || normalizedKey.contains("apikey")
                || normalizedKey.contains("secret")
                || normalizedKey.contains("token")) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            rawMap.forEach((rawKey, rawValue) -> {
                String childKey = stringValue(rawKey, "");
                sanitized.put(childKey, sanitizeProviderPayload(rawValue, childKey));
            });
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : collection) {
                sanitized.add(sanitizeProviderPayload(item, key));
            }
            return sanitized;
        }
        if (value instanceof byte[] bytes) {
            return "[binary bytes=" + bytes.length + "]";
        }
        if (value instanceof String text) {
            if (text.startsWith("data:") && text.length() > 80) {
                return "[data URI chars=" + text.length() + " omitted]";
            }
            if (isLikelyBase64PayloadKey(normalizedKey) && text.length() > 80) {
                return "[base64 chars=" + text.length() + " omitted]";
            }
            if ((normalizedKey.contains("url") || normalizedKey.contains("uri") || normalizedKey.contains("source"))
                    && text.startsWith("http")) {
                return redactUrl(text);
            }
        }
        return value;
    }

    private boolean isLikelyBase64PayloadKey(String normalizedKey) {
        return normalizedKey.equals("data")
                || normalizedKey.equals("imagebytes")
                || normalizedKey.equals("audiocontent")
                || normalizedKey.equals("videocontent")
                || normalizedKey.equals("bytesbase64encoded")
                || normalizedKey.equals("b64_json")
                || normalizedKey.equals("b64json")
                || normalizedKey.equals("base64")
                || normalizedKey.endsWith("base64");
    }

    private Object firstValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object firstPresentValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            if (value instanceof Collection<?> collection && collection.isEmpty()) {
                continue;
            }
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                continue;
            }
            return value;
        }
        return null;
    }

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        if (values == null) {
            return BigDecimal.ZERO;
        }
        for (BigDecimal value : values) {
            if (value != null && value.signum() > 0) {
                return value;
            }
        }
        return BigDecimal.ZERO;
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }
        return new LinkedHashMap<>();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Collection<?> collection && collection.isEmpty()) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private BigDecimal positiveDecimal(String prefix, String altPrefix, String primarySuffix, String fallbackSuffix) {
        return firstPositive(
                envDecimal(prefix + "_" + primarySuffix, BigDecimal.ZERO),
                envDecimal(altPrefix + "_" + primarySuffix, BigDecimal.ZERO),
                envDecimal(prefix + "_" + fallbackSuffix, BigDecimal.ZERO),
                envDecimal(altPrefix + "_" + fallbackSuffix, BigDecimal.ZERO),
                BigDecimal.ZERO
        );
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "y", "on").contains(normalized)) {
            return true;
        }
        if (List.of("false", "0", "no", "n", "off").contains(normalized)) {
            return false;
        }
        return fallback;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int googleVeoDurationSeconds(int requestedSeconds) {
        if (requestedSeconds <= 4) {
            return 4;
        }
        if (requestedSeconds <= 6) {
            return 6;
        }
        return 8;
    }

    static String normalizeFalSeedanceModel(String model) {
        String normalized = (model == null ? "" : model.trim().toLowerCase(Locale.ROOT))
                .replace('_', '-');
        normalized = normalized
                .replaceAll("/(text-to-video|image-to-video|reference-to-video)$", "")
                .replaceAll("/+$", "");
        if (normalized.contains("1.5") || normalized.contains("1-5") || normalized.contains("v1.5")) {
            return "fal-ai/bytedance/seedance/v1.5/pro";
        }
        if (normalized.contains("1.0") || normalized.contains("1-0") || normalized.contains("/v1/pro")) {
            return "fal-ai/bytedance/seedance/v1/pro";
        }
        if (normalized.contains("fast")) {
            return "bytedance/seedance-2.0/fast";
        }
        return "bytedance/seedance-2.0";
    }

    static String falSeedanceEndpoint(String model, boolean imageToVideo) {
        return falSeedanceEndpoint(model, imageToVideo, false);
    }

    static String falSeedanceEndpoint(String model, boolean imageToVideo, boolean referenceToVideo) {
        String normalizedModel = normalizeFalSeedanceModel(model);
        if (referenceToVideo && supportsFalSeedanceReferenceToVideo(normalizedModel)) {
            return normalizedModel + "/reference-to-video";
        }
        return normalizedModel + (imageToVideo ? "/image-to-video" : "/text-to-video");
    }

    static boolean supportsFalSeedanceReferenceToVideo(String model) {
        return normalizeFalSeedanceModel(model).startsWith("bytedance/seedance-2.0");
    }

    static int falSeedanceDuration(int requestedSeconds) {
        return Math.max(4, Math.min(15, requestedSeconds));
    }

    static int falSeedanceDuration(String model, int requestedSeconds) {
        String normalized = normalizeFalSeedanceModel(model);
        int maximum = normalized.contains("v1.5") || normalized.contains("/v1/") ? 12 : 15;
        return Math.max(4, Math.min(maximum, requestedSeconds));
    }

    static String falSeedanceResolution(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return List.of("480p", "720p", "1080p").contains(normalized) ? normalized : "720p";
    }

    static String falSeedanceResolution(String model, String value) {
        String resolution = falSeedanceResolution(value);
        return normalizeFalSeedanceModel(model).contains("/fast") && "1080p".equals(resolution)
                ? "720p"
                : resolution;
    }

    static String falSeedanceAspectRatio(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return List.of("auto", "21:9", "16:9", "4:3", "1:1", "3:4", "9:16").contains(normalized)
                ? normalized
                : "9:16";
    }

    static String falSeedanceBitrateMode(String value) {
        return "high".equalsIgnoreCase(value == null ? "" : value.trim()) ? "high" : "standard";
    }

    static BigDecimal falSeedanceRatePerSecond(String model) {
        return falSeedanceRatePerSecond(model, false);
    }

    static BigDecimal falSeedanceRatePerSecond(String model, boolean imageToVideo) {
        String normalized = normalizeFalSeedanceModel(model);
        if (normalized.contains("/fast")) {
            return new BigDecimal("0.2419");
        }
        if (normalized.contains("v1.5")) {
            return new BigDecimal("0.0520");
        }
        return imageToVideo ? new BigDecimal("0.3024") : new BigDecimal("0.3034");
    }

    private String normalizeProvider(String provider) {
        String normalized = stringValue(provider, "seedance").toLowerCase(Locale.ROOT).replace('-', '_').trim();
        if (normalized.equals("gemini_omni")
                || normalized.equals("google_omni")
                || normalized.equals("omni_flash")
                || normalized.equals("omini_flash")
                || normalized.equals("gemini_omni_flash")
                || normalized.equals("google_omni_flash")
                || normalized.equals("gemini_omni_flash_preview")) {
            return "gemini_omni";
        }
        if (normalized.equals("omni") || normalized.equals("omini") || normalized.equals("openai_omni") || normalized.equals("openai_omini")) {
            return "omini";
        }
        if (normalized.equals("veo") || normalized.equals("google_veo") || normalized.equals("google_video") || normalized.equals("vertex_veo")) {
            return "google_veo";
        }
        if (normalized.equals("seed_dance")
                || normalized.equals("byteplus_seedance")
                || normalized.equals("volcengine_seedance")
                || normalized.equals("fal_seedance")
                || normalized.equals("fal_ai_seedance")) {
            return "seedance";
        }
        if (normalized.equals("synthesia") || normalized.equals("synthesia_api") || normalized.equals("api_synthesia")) {
            return "synthesia";
        }
        if (normalized.equals("dalai_llama")
                || normalized.equals("dallai_llama")
                || normalized.equals("local")
                || normalized.equals("local_avatar")
                || normalized.equals("local_open_source")
                || normalized.equals("open_source")
                || normalized.equals("opensource")) {
            return "dalai_llama";
        }
        return normalized.isBlank() ? "seedance" : normalized;
    }

    private boolean googleVeoUsesVertex() {
        String backend = firstText(
                envString("GOOGLE_VEO_BACKEND", ""),
                envString("CREATOR_GOOGLE_GENAI_BACKEND", ""),
                envString("GOOGLE_GENAI_BACKEND", ""),
                "ai_studio"
        ).toLowerCase(Locale.ROOT).replace('-', '_');
        return backend.equals("vertex") || backend.equals("vertex_ai");
    }

    private String normalizeGoogleVeoModel(String model) {
        String value = firstText(model, "veo-3.1-generate-preview");
        if ("veo-3.1-generate-001".equals(value)) {
            return "veo-3.1-generate-preview";
        }
        if ("veo-3.1-fast-generate-001".equals(value)) {
            return "veo-3.1-fast-generate-preview";
        }
        return value;
    }

    private String googleVeoBaseUrl(String model, boolean googleAiStudio) {
        if (googleAiStudio) {
            return "https://generativelanguage.googleapis.com/v1beta";
        }
        String projectId = firstText(
                envString("GOOGLE_VEO_PROJECT_ID", ""),
                envString("GOOGLE_CLOUD_PROJECT_ID", ""),
                envString("GOOGLE_CLOUD_PROJECT", "")
        );
        if (projectId.isBlank()) {
            return "";
        }
        String location = firstText(
                envString("GOOGLE_VEO_LOCATION", ""),
                envString("GOOGLE_CLOUD_LOCATION", ""),
                "us-central1"
        );
        return "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s"
                .formatted(location, projectId, location, model);
    }

    private String googleVeoStartPath(String model, boolean googleAiStudio) {
        if (googleAiStudio) {
            return "/models/%s:predictLongRunning".formatted(model);
        }
        return ":predictLongRunning";
    }

    private String googleVeoPollPath(boolean googleAiStudio) {
        return googleAiStudio ? "/{id}" : ":fetchPredictOperation";
    }

    private String textValue(Map<String, Object> map, String key) {
        return stringValue(map == null ? null : map.get(key), "");
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String envString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long envLong(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private BigDecimal envDecimal(String name, BigDecimal fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean booleanEnv(String name, boolean fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String trimLeadingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    private String truncatePromptAtSectionBoundary(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int safeMax = Math.max(200, maxLength - 140);
        int boundary = value.lastIndexOf("\n\n", safeMax);
        if (boundary < safeMax / 2) {
            boundary = value.lastIndexOf('\n', safeMax);
        }
        if (boundary < safeMax / 2) {
            boundary = safeMax;
        }
        return value.substring(0, Math.max(0, boundary)).trim()
                + "\n\n[Prompt shortened by backend at a section boundary. Use only the complete instructions above; do not infer from partial JSON.]";
    }

    private void sleep(long millis, String label) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + label + ".", ex);
        }
    }

    private static final class HeaderCaptureInputStream extends FilterInputStream {

        private final ByteArrayOutputStream header = new ByteArrayOutputStream();
        private final int maxHeaderBytes;
        private long totalBytes;

        private HeaderCaptureInputStream(InputStream in, int maxHeaderBytes) {
            super(in);
            this.maxHeaderBytes = Math.max(0, maxHeaderBytes);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                totalBytes++;
                if (header.size() < maxHeaderBytes) {
                    header.write(value);
                }
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                totalBytes += read;
                int remaining = maxHeaderBytes - header.size();
                if (remaining > 0) {
                    header.write(bytes, offset, Math.min(read, remaining));
                }
            }
            return read;
        }

        private byte[] capturedBytes() {
            return header.toByteArray();
        }

        private long totalBytes() {
            return totalBytes;
        }
    }

    private record ProviderConfig(
            String provider,
            String prefix,
            String altPrefix,
            String apiKey,
            String baseUrl,
            String startPath,
            String pollPath,
            String model,
            int maxClipSeconds,
            int maxConcurrentGenerations,
            long requestMinIntervalMs,
            long pollIntervalMs,
            long timeoutMs,
            int maxAttempts,
            long retryBackoffMs,
            String authHeader,
            String authPrefix,
            BigDecimal ratePerSecondUsd,
            BigDecimal ratePerClipUsd,
            BigDecimal ratePerMillionTokensUsd,
            boolean allowUnpricedProviderCalls,
            boolean googleAiStudio
    ) {
    }

    public record SceneVideoRequest(
            String runId,
            String scriptId,
            String sceneId,
            int sceneNumber,
            String provider,
            String model,
            String prompt,
            int durationSeconds,
            String aspectRatio,
            String generationMode,
            long seriesSeed,
            long seed,
            Map<String, Object> run,
            Map<String, Object> scene,
            Map<String, Object> providerRequest,
            Map<String, Object> videoConsistencyBible,
            Map<String, Object> seedancePromptStrategy,
            Map<String, Object> videoPacingProfile,
            List<Object> srtCues,
            Map<String, Object> previousScene,
            Map<String, Object> nextScene,
            String targetObjectKey,
            Duration signedUrlTtl
    ) {
    }

    private record StreamedAvatarScene(
            AssetStorageService.StoredObject storedObject,
            Map<String, Object> providerResponse,
            String contentType
    ) {
    }

    private record DownloadedReferenceImage(
            byte[] bytes,
            String contentType,
            String redactedSource,
            String role
    ) {
    }

    public record GeneratedSceneVideo(
            byte[] bytes,
            AssetStorageService.StoredObject storedObject,
            String contentType,
            Map<String, Object> metadata,
            String operationName,
            Map<String, Object> providerRequest,
            Map<String, Object> providerResponse
    ) {
    }

    public static class ManualAvatarFallbackRequiredException extends RuntimeException {

        private final Map<String, Object> providerRequest;
        private final Map<String, Object> providerResponse;

        public ManualAvatarFallbackRequiredException(
                String message,
                Map<String, Object> providerRequest,
                Map<String, Object> providerResponse
        ) {
            super(message);
            this.providerRequest = providerRequest == null ? Map.of() : new LinkedHashMap<>(providerRequest);
            this.providerResponse = providerResponse == null ? Map.of() : new LinkedHashMap<>(providerResponse);
        }

        public Map<String, Object> providerRequest() {
            return providerRequest;
        }

        public Map<String, Object> providerResponse() {
            return providerResponse;
        }
    }
}
