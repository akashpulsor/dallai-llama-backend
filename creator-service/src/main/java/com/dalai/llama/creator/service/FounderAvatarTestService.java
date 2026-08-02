package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class FounderAvatarTestService {

    private static final Logger log = LoggerFactory.getLogger(FounderAvatarTestService.class);
    private static final String DEFAULT_AI_SERVICE_URL = "http://ai-service.apps.svc.cluster.local:8601";
    private static final String DEFAULT_SCENE_FILE_PATH = "/creator/avatar/scenes/files";
    private static final String ASSET_TYPE_FOUNDER_AVATAR_PORTRAIT = "FOUNDER_AVATAR_PORTRAIT";
    private static final String ASSET_TYPE_FOUNDER_AVATAR_TEST = "FOUNDER_AVATAR_TEST_VIDEO";
    private static final String AVATAR_METADATA_HEADER = "X-Dalai-Avatar-Metadata";
    private static final String HAPPY_HORSE_MODEL = "fal_happy_horse_v1_1";
    private static final String HAPPY_HORSE_ENDPOINT = "alibaba/happy-horse/v1.1/image-to-video";
    private static final String HEYGEN_AVATAR4_MODEL = "fal_heygen_avatar4";
    private static final String HEYGEN_AVATAR4_ENDPOINT = "fal-ai/heygen/avatar4/image-to-video";
    private static final String LATENT_SYNC_MODEL = "fal_latentsync";
    private static final String LATENT_SYNC_ENDPOINT = "fal-ai/latentsync";
    private static final String MUSE_TALK_MODEL = "fal_musetalk";
    private static final String MUSE_TALK_ENDPOINT = "fal-ai/musetalk";
    private static final String AVATAR_NATIVE_MODEL = "avatar_native";
    private static final String COMBINED_PROVIDER_MODEL = HAPPY_HORSE_ENDPOINT + "+" + LATENT_SYNC_ENDPOINT;
    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    private static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(2);
    private static final double HAPPY_HORSE_1080P_USD_PER_SECOND = 0.18d;
    private static final double HEYGEN_AVATAR4_USD_PER_SECOND = 0.10d;
    private static final double LATENT_SYNC_TEST_COST_USD = 0.20d;

    private final CreatorScriptRepository scriptRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final String configuredAiServiceUrl;
    private final long timeoutMs;

    private record StreamedTest(
            AssetStorageService.StoredObject storedObject,
            Map<String, Object> providerResponse
    ) {
    }

    public FounderAvatarTestService(
            CreatorScriptRepository scriptRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            CreatorAiService creatorAiService,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder,
            @Value("${creator.avatar-preview.ai-service-url:}") String configuredAiServiceUrl,
            @Value("${creator.avatar-preview.timeout-ms:900000}") long timeoutMs
    ) {
        this.scriptRepository = scriptRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
        this.configuredAiServiceUrl = configuredAiServiceUrl;
        this.timeoutMs = Math.max(120000, timeoutMs);
    }

    @Transactional
    public Map<String, Object> preparePortrait(
            UUID scriptId,
            MultipartFile file,
            String sourceMode,
            Double timestampSeconds,
            boolean consentConfirmed,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> profile = persistedProfile(script);
        if (profile.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload the creator video before preparing an avatar portrait.");
        }
        if (!consentConfirmed && !booleanValue(profile.get("consentConfirmed"), false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Creator consent is required before avatar processing.");
        }

        String mode = normalizePortraitMode(sourceMode, file);
        Map<String, Object> portraitAsset;
        if ("upload".equals(mode)) {
            portraitAsset = storeUploadedPortrait(script, file);
        } else {
            portraitAsset = extractPortraitFromSource(script, profile, timestampSeconds);
        }

        profile.put("avatarPortraitAsset", portraitAsset);
        profile.put("avatarPortraitUrl", firstText(portraitAsset.get("assetUrl")));
        profile.put("avatarPortraitSourceMode", mode);
        profile.put("avatarPortraitStatus", "READY");
        profile.put("avatarPortraitPreparedAt", OffsetDateTime.now().toString());
        resetAvatarTest(profile);
        attachProfile(script, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "READY");
        response.put("portraitAsset", portraitAsset);
        response.put("sourceMode", mode);
        response.put("founderAvatarProfile", profile);
        return response;
    }

    @Transactional
    public Map<String, Object> generateTest(
            UUID scriptId,
            Map<String, Object> requestBody,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> request = copyMap(requestBody);
        Map<String, Object> profile = persistedProfile(script);
        if (profile.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload the creator source before creating an avatar test.");
        }
        if (!booleanValue(profile.get("consentConfirmed"), false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Creator consent is required before avatar processing.");
        }
        if (!"APPROVED".equalsIgnoreCase(firstText(profile.get("voiceApprovalStatus")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approve the cloned voice preview before creating an avatar test.");
        }

        String portraitMode = normalizePortraitMode(
                firstText(request.get("portraitMode"), profile.get("avatarPortraitSourceMode")),
                null
        );
        Map<String, Object> portraitAsset = firstMap(profile.get("avatarPortraitAsset"));
        if (portraitAsset.isEmpty() && "extract".equals(portraitMode)) {
            portraitAsset = extractPortraitFromSource(
                    script,
                    profile,
                    doubleValue(request.get("portraitTimestampSeconds"), 0.5d)
            );
            profile.put("avatarPortraitAsset", portraitAsset);
            profile.put("avatarPortraitUrl", firstText(portraitAsset.get("assetUrl")));
            profile.put("avatarPortraitSourceMode", "extract");
            profile.put("avatarPortraitStatus", "READY");
            profile.put("avatarPortraitPreparedAt", OffsetDateTime.now().toString());
        }
        if (portraitAsset.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload or extract a founder portrait before creating the avatar test.");
        }

        Map<String, Object> voiceAsset = firstMap(
                profile.get("voicePreviewAsset"),
                profile.get("exactFounderAudioAsset"),
                profile.get("finalFounderAudioAsset")
        );
        String portraitObjectKey = firstText(portraitAsset.get("objectKey"));
        String voiceObjectKey = firstText(voiceAsset.get("objectKey"));
        if (portraitObjectKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The founder portrait is not available in storage.");
        }
        if (voiceObjectKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The approved voice preview is not available in storage.");
        }

        String requestId = firstText(request.get("requestId"), "avatar-test-" + UUID.randomUUID());
        int durationSeconds = clampInt(request.get("durationSeconds"), 3, 5, 5);
        String aspectRatio = firstText(request.get("aspectRatio"), "9:16");
        String dialogue = firstText(
                request.get("previewText"),
                request.get("dialogueText"),
                profile.get("voicePreviewText"),
                profile.get("spokenText")
        );
        String motionPrompt = firstText(
                request.get("motionPrompt"),
                profile.get("avatarMotionPrompt"),
                "Natural founder speaking to camera with restrained head, shoulder, and hand movement. Preserve identity, hairstyle, clothing, lighting, and background."
        );
        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(profile.get("localModels")));
        String avatarModel = normalizeAvatarModel(firstText(
                request.get("talkingAvatarModel"),
                localModels.get("talkingAvatarModel"),
                HEYGEN_AVATAR4_MODEL
        ));
        String lipSyncModel = HEYGEN_AVATAR4_MODEL.equals(avatarModel)
                ? AVATAR_NATIVE_MODEL
                : normalizeLipSyncModel(firstText(
                        request.get("lipSyncModel"),
                        localModels.get("lipSyncModel"),
                        LATENT_SYNC_MODEL
                ));
        String avatarResolution = normalizeAvatarResolution(
                firstText(request.get("avatarResolution"), localModels.get("avatarResolution")),
                avatarModel
        );
        String talkingStyle = normalizeTalkingStyle(firstText(
                request.get("talkingStyle"),
                localModels.get("talkingStyle"),
                "stable"
        ));
        String expression = firstText(request.get("expression"), profile.get("avatarExpression"));
        Map<String, Object> avatarBackground = firstMap(
                request.get("avatarBackground"),
                profile.get("avatarBackground")
        );
        localModels.put("talkingAvatarModel", avatarModel);
        localModels.put("lipSyncModel", lipSyncModel);
        localModels.put("avatarResolution", avatarResolution);
        localModels.put("talkingStyle", talkingStyle);

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                null,
                null
        );
        creatorAiService.assertWalletBalanceForModelRun("FOUNDER_AVATAR_TEST", usageContext);
        long startedNanos = System.nanoTime();
        StreamedTest generated = generateManagedAvatarTest(
                script,
                profile,
                localModels,
                portraitAsset,
                voiceAsset,
                portraitObjectKey,
                voiceObjectKey,
                requestId,
                dialogue,
                firstText(profile.get("language"), "English"),
                firstText(profile.get("languageCode"), "en-IN"),
                durationSeconds,
                aspectRatio,
                motionPrompt,
                avatarModel,
                lipSyncModel,
                avatarResolution,
                talkingStyle,
                expression,
                avatarBackground
        );
        Map<String, Object> providerResponse = generated.providerResponse();
        Map<String, Object> costMetadata = firstMap(providerResponse.get("costMetadata"));
        creatorAiService.publishProviderUsageDebit(
                "FOUNDER_AVATAR_TEST",
                firstText(costMetadata.get("provider"), "fal.ai"),
                firstText(costMetadata.get("model"), combinedProviderModel(avatarModel, lipSyncModel)),
                costMetadata,
                usageContext,
                "Generated founder avatar quality test"
        );

        Map<String, Object> testAsset = storeTestAsset(
                script,
                requestId,
                generated.storedObject(),
                providerResponse,
                costMetadata,
                portraitObjectKey,
                voiceObjectKey
        );
        profile.put("localModels", localModels);
        profile.put("avatarTestLocalModels", localModels);
        profile.put("avatarMotionPrompt", motionPrompt);
        profile.put("avatarTestAsset", testAsset);
        profile.put("avatarTestUrl", firstText(testAsset.get("assetUrl")));
        profile.put("avatarTestStatus", "TEST_READY");
        profile.put("avatarTestGeneratedAt", OffsetDateTime.now().toString());
        profile.put("avatarTestRequestId", requestId);
        profile.put("avatarTestModel", avatarModel);
        profile.put("avatarTestLipSyncModel", lipSyncModel);
        profile.put("avatarTestDurationSeconds", durationSeconds);
        profile.put("avatarPreviewStatus", "NOT_REQUESTED");
        profile.remove("avatarPreviewApprovedAt");
        profile.remove("avatarPreviewApprovedBy");
        attachProfile(script, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "TEST_READY");
        response.put("requestId", requestId);
        response.put("testAsset", testAsset);
        response.put("avatarModel", avatarModel);
        response.put("founderAvatarProfile", profile);
        log.info(
                "Founder avatar test completed requestId={} scriptId={} elapsedMs={} bytes={} model={} lipSyncModel={}",
                requestId,
                scriptId,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                generated.storedObject().sizeBytes(),
                avatarModel,
                lipSyncModel
        );
        return response;
    }

    private Map<String, Object> storeUploadedPortrait(CreatorScript script, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a founder portrait image to upload.");
        }
        String contentType = firstText(file.getContentType());
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                && isSupportedImageFilename(file.getOriginalFilename())) {
            contentType = imageContentTypeForFilename(file.getOriginalFilename());
        }
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Founder portrait must be a JPG, PNG, or WebP image.");
        }
        String originalFilename = firstText(file.getOriginalFilename(), "founder-portrait");
        String objectKey = "screenplay-videos/%s/founder-avatar/portraits/%s.%s".formatted(
                script.getId(),
                UUID.randomUUID(),
                imageFileExtension(contentType)
        );
        try (InputStream inputStream = file.getInputStream()) {
            AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAssetFromStream(
                    objectKey,
                    inputStream,
                    contentType,
                    SIGNED_URL_TTL
            );
            return persistPortraitAsset(script, stored, originalFilename, "upload", null);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded founder portrait.", ex);
        }
    }

    private Map<String, Object> extractPortraitFromSource(
            CreatorScript script,
            Map<String, Object> profile,
            Double requestedTimestamp
    ) {
        Map<String, Object> sourceAsset = firstMap(profile.get("sourceAsset"));
        String sourceObjectKey = firstText(sourceAsset.get("objectKey"), profile.get("sourceObjectKey"));
        if (sourceObjectKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The creator source video is not available in storage.");
        }
        String sourceBucket = firstText(sourceAsset.get("bucket"), assetStorageService.creatorAssetsBucket());
        double timestampSeconds = Math.max(0.0d, Math.min(60.0d, requestedTimestamp == null ? 0.5d : requestedTimestamp));
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("founder-avatar-portrait-");
            Path sourcePath = workspace.resolve("creator-source" + mediaFileExtension(
                    firstText(sourceAsset.get("contentType"), profile.get("sourceContentType")),
                    sourceObjectKey
            ));
            Path portraitPath = workspace.resolve("founder-portrait.jpg");
            assetStorageService.downloadObjectToPath(sourceBucket, sourceObjectKey, sourcePath);
            try {
                extractFrame(sourcePath, portraitPath, timestampSeconds);
            } catch (ResponseStatusException ex) {
                if (timestampSeconds <= 0.0d) {
                    throw ex;
                }
                Files.deleteIfExists(portraitPath);
                extractFrame(sourcePath, portraitPath, 0.0d);
                timestampSeconds = 0.0d;
            }
            String objectKey = "screenplay-videos/%s/founder-avatar/portraits/%s.jpg".formatted(
                    script.getId(),
                    UUID.randomUUID()
            );
            AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAssetFromPath(
                    objectKey,
                    portraitPath,
                    MediaType.IMAGE_JPEG_VALUE,
                    SIGNED_URL_TTL
            );
            return persistPortraitAsset(
                    script,
                    stored,
                    "founder-portrait-extracted.jpg",
                    "extract",
                    timestampSeconds
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not extract a portrait from the creator video.", ex);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private void extractFrame(Path sourcePath, Path portraitPath, double timestampSeconds) {
        List<String> command = List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", timestampSeconds),
                "-i", sourcePath.toString(),
                "-frames:v", "1",
                "-vf", "scale=1080:-2:force_original_aspect_ratio=decrease",
                "-q:v", "2",
                portraitPath.toString()
        );
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(FFMPEG_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Founder portrait extraction timed out.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || !Files.isRegularFile(portraitPath) || Files.size(portraitPath) == 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Founder portrait extraction failed: " + tail(output, 1200)
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Founder portrait extraction was interrupted.", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg is not available for founder portrait extraction.", ex);
        }
    }

    private Map<String, Object> persistPortraitAsset(
            CreatorScript script,
            AssetStorageService.StoredObject stored,
            String originalFilename,
            String sourceMode,
            Double timestampSeconds
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        metadata.put("assetRole", "founder_avatar_portrait");
        metadata.put("sourceMode", sourceMode);
        metadata.put("timestampSeconds", timestampSeconds);
        metadata.put("originalFilename", originalFilename);
        metadata.put("storageProvider", "minio");
        metadata.put("storageStatus", "SAVED_TO_MINIO");

        CreatorAsset savedAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_FOUNDER_AVATAR_PORTRAIT)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetId", savedAsset.getId().toString());
        asset.put("id", savedAsset.getId().toString());
        asset.put("assetType", ASSET_TYPE_FOUNDER_AVATAR_PORTRAIT);
        asset.put("assetRole", "founder_avatar_portrait");
        asset.put("bucket", stored.bucket());
        asset.put("objectKey", stored.objectKey());
        asset.put("contentType", stored.contentType());
        asset.put("sizeBytes", stored.sizeBytes());
        asset.put("assetUrl", stored.signedUrl());
        asset.put("signedUrl", stored.signedUrl());
        asset.put("publicUrl", stored.signedUrl());
        asset.put("originalFilename", originalFilename);
        asset.put("sourceMode", sourceMode);
        asset.put("timestampSeconds", timestampSeconds);
        asset.put("metadata", metadata);
        return asset;
    }

    private StreamedTest generateManagedAvatarTest(
            CreatorScript script,
            Map<String, Object> profile,
            Map<String, Object> localModels,
            Map<String, Object> portraitAsset,
            Map<String, Object> voiceAsset,
            String portraitObjectKey,
            String voiceObjectKey,
            String requestId,
            String dialogue,
            String language,
            String languageCode,
            int durationSeconds,
            String aspectRatio,
            String motionPrompt,
            String avatarModel,
            String lipSyncModel,
            String avatarResolution,
            String talkingStyle,
            String expression,
            Map<String, Object> avatarBackground
    ) {
        String portraitBucket = firstText(portraitAsset.get("bucket"), assetStorageService.creatorAssetsBucket());
        String voiceBucket = firstText(voiceAsset.get("bucket"), assetStorageService.creatorAssetsBucket());
        String outputObjectKey = "screenplay-videos/%s/founder-avatar/tests/%s.mp4".formatted(
                script.getId(),
                requestId
        );
        try (
                AssetStorageService.StreamedObject portrait = assetStorageService.openObjectStream(portraitBucket, portraitObjectKey);
                AssetStorageService.StreamedObject voice = assetStorageService.openObjectStream(voiceBucket, voiceObjectKey)
        ) {
            MultipartBodyBuilder multipart = new MultipartBodyBuilder();
            multipart.part(
                            "sourceVideo",
                            streamResource(portrait, firstText(portraitAsset.get("originalFilename"), "founder-portrait.jpg"))
                    )
                    .filename(firstText(portraitAsset.get("originalFilename"), "founder-portrait.jpg"))
                    .contentType(mediaType(portrait.contentType(), MediaType.IMAGE_JPEG));
            multipart.part("dialogueAudio", streamResource(voice, "approved-founder-voice.wav"))
                    .filename("approved-founder-voice.wav")
                    .contentType(mediaType(voice.contentType(), MediaType.APPLICATION_OCTET_STREAM));
            addPart(multipart, "runId", requestId);
            addPart(multipart, "scriptId", script.getId());
            addPart(multipart, "sceneId", "founder-avatar-test");
            addPart(multipart, "sceneNumber", 1);
            addPart(multipart, "provider", "dalai_llama");
            addPart(multipart, "model", avatarModel);
            addPart(multipart, "durationSeconds", durationSeconds);
            addPart(multipart, "aspectRatio", aspectRatio);
            addPart(multipart, "prompt", motionPrompt);
            addPart(multipart, "dialogueScript", dialogue);
            addPart(multipart, "exactDialogue", dialogue);
            addPart(multipart, "language", language);
            addPart(multipart, "languageCode", languageCode);
            addPart(multipart, "generationMode", "avatar_test");
            addPart(multipart, "founderAvatarProfileJson", json(profile));
            addPart(multipart, "localModelsJson", json(localModels));
            addPart(multipart, "voiceModel", firstText(localModels.get("voiceModel"), "client_rvc_english"));
            addPart(multipart, "talkingAvatarModel", avatarModel);
            addPart(multipart, "imageModel", firstText(localModels.get("imageModel"), "gemini_storyboard"));
            addPart(multipart, "lightingModel", firstText(localModels.get("lightingModel"), "ic_lightning"));
            addPart(multipart, "lipSyncModel", lipSyncModel);
            addPart(multipart, "videoModel", firstText(localModels.get("videoModel"), "fal_seedance"));
            addPart(multipart, "gpuProfile", firstText(localModels.get("gpuProfile"), "rtx_4060_8gb"));
            addPart(multipart, "consentConfirmed", true);
            addPart(multipart, "manualApprovalRequiredForFallback", true);
            addPart(multipart, "productionEnhancementEnabled", false);
            addPart(multipart, "talkingStyle", talkingStyle);
            addPart(multipart, "expression", expression);
            addPart(multipart, "avatarResolution", avatarResolution);
            addPart(multipart, "avatarCaption", false);
            addPart(multipart, "avatarBackgroundJson", json(avatarBackground));

            log.info(
                    "Founder avatar test handoff started requestId={} scriptId={} portraitBytes={} voiceBytes={} model={} lipSyncModel={} durationSeconds={}",
                    requestId,
                    script.getId(),
                    portrait.sizeBytes(),
                    voice.sizeBytes(),
                    avatarModel,
                    lipSyncModel,
                    durationSeconds
            );
            Path responsePath = Files.createTempFile("founder-avatar-test-", ".mp4");
            try {
                StreamedTest test = aiClient()
                        .post()
                        .uri(firstText(
                                System.getenv("DALAI_LLAMA_AVATAR_SCENE_FILE_PATH"),
                                System.getenv("DALLAI_LLAMA_AVATAR_SCENE_FILE_PATH"),
                                DEFAULT_SCENE_FILE_PATH
                        ))
                        .headers(this::applyAuth)
                        .accept(MediaType.valueOf("video/mp4"))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .bodyValue(multipart.build())
                        .exchangeToMono(response -> {
                            if (!response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(body -> Mono.error(new ResponseStatusException(
                                                response.statusCode(),
                                                "ai-service rejected avatar test: " + firstText(body, response.statusCode())
                                        )));
                            }
                            String contentType = response.headers().contentType()
                                    .map(MediaType::toString)
                                    .orElse(MediaType.valueOf("video/mp4").toString());
                            Map<String, Object> providerResponse = decodeProviderResponse(
                                    response.headers().asHttpHeaders(),
                                    durationSeconds,
                                    avatarModel,
                                    lipSyncModel
                            );
                            return DataBufferUtils.write(
                                            response.bodyToFlux(DataBuffer.class),
                                            responsePath,
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.TRUNCATE_EXISTING,
                                            StandardOpenOption.WRITE
                                    )
                                    .then(Mono.fromCallable(() -> {
                                        long streamedBytes = Files.size(responsePath);
                                        if (streamedBytes <= 0) {
                                            throw new IllegalStateException("ai-service returned an empty avatar test stream.");
                                        }
                                        AssetStorageService.StoredObject stored =
                                                assetStorageService.uploadCreatorAssetFromPath(
                                                        outputObjectKey,
                                                        responsePath,
                                                        contentType,
                                                        SIGNED_URL_TTL
                                                );
                                        log.info(
                                                "Founder avatar test stream saved requestId={} bytes={} objectKey={} falRequestId={}",
                                                requestId,
                                                stored.sizeBytes(),
                                                stored.objectKey(),
                                                firstText(providerResponse.get("falRequestId"))
                                        );
                                        return new StreamedTest(stored, providerResponse);
                                    }).subscribeOn(Schedulers.boundedElastic()));
                        })
                        .block(Duration.ofMillis(timeoutMs));
                if (test == null) {
                    throw new IllegalStateException("ai-service returned no avatar test stream.");
                }
                return test;
            } finally {
                Files.deleteIfExists(responsePath);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not stream the portrait and approved voice from storage.",
                    ex
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error(
                    "Founder avatar test handoff failed requestId={} scriptId={} errorType={} errorMessage={}",
                    requestId,
                    script.getId(),
                    ex.getClass().getSimpleName(),
                    firstText(ex.getMessage(), "unknown")
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Avatar quality test failed before completion. Reference: " + requestId,
                    ex
            );
        }
    }

    private Map<String, Object> storeTestAsset(
            CreatorScript script,
            String requestId,
            AssetStorageService.StoredObject stored,
            Map<String, Object> providerResponse,
            Map<String, Object> costMetadata,
            String portraitObjectKey,
            String voiceObjectKey
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        metadata.put("requestId", requestId);
        metadata.put("assetRole", "founder_avatar_test");
        metadata.put("provider", firstText(costMetadata.get("provider"), "fal.ai"));
        metadata.put(
                "model",
                firstText(
                        costMetadata.get("model"),
                        combinedProviderModel(
                                firstText(providerResponse.get("avatarModel"), HEYGEN_AVATAR4_MODEL),
                                firstText(providerResponse.get("lipSyncModel"), AVATAR_NATIVE_MODEL)
                        )
                )
        );
        metadata.put("avatarModel", firstText(providerResponse.get("avatarModel"), HEYGEN_AVATAR4_MODEL));
        metadata.put("lipSyncModel", firstText(providerResponse.get("lipSyncModel"), AVATAR_NATIVE_MODEL));
        metadata.put("falRequestId", firstText(providerResponse.get("falRequestId")));
        metadata.put("avatarRequestId", firstText(providerResponse.get("avatarRequestId")));
        metadata.put("lipSyncRequestId", firstText(providerResponse.get("lipSyncRequestId")));
        metadata.put("portraitObjectKey", portraitObjectKey);
        metadata.put("voiceObjectKey", voiceObjectKey);
        metadata.put("storageProvider", "minio");
        metadata.put("storageStatus", "SAVED_TO_MINIO");
        if (!costMetadata.isEmpty()) {
            metadata.put("costMetadata", costMetadata);
        }

        CreatorAsset savedAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_FOUNDER_AVATAR_TEST)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetId", savedAsset.getId().toString());
        asset.put("id", savedAsset.getId().toString());
        asset.put("assetType", ASSET_TYPE_FOUNDER_AVATAR_TEST);
        asset.put("assetRole", "founder_avatar_test");
        asset.put("bucket", stored.bucket());
        asset.put("objectKey", stored.objectKey());
        asset.put("contentType", stored.contentType());
        asset.put("sizeBytes", stored.sizeBytes());
        asset.put("assetUrl", stored.signedUrl());
        asset.put("signedUrl", stored.signedUrl());
        asset.put("publicUrl", stored.signedUrl());
        asset.put("metadata", metadata);
        asset.put("generatedAt", OffsetDateTime.now().toString());
        return asset;
    }

    private InputStreamResource streamResource(AssetStorageService.StreamedObject object, String filename) {
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

    private Map<String, Object> decodeProviderResponse(
            HttpHeaders headers,
            int durationSeconds,
            String requestedAvatarModel,
            String requestedLipSyncModel
    ) {
        String avatarModel = normalizeAvatarModel(requestedAvatarModel);
        String lipSyncModel = normalizeLipSyncModel(requestedLipSyncModel);
        String encoded = firstText(headers.getFirst(AVATAR_METADATA_HEADER));
        if (encoded.isBlank()) {
            double avatarCost = Math.round(
                    durationSeconds * (
                            HEYGEN_AVATAR4_MODEL.equals(avatarModel)
                                    ? HEYGEN_AVATAR4_USD_PER_SECOND
                                    : HAPPY_HORSE_1080P_USD_PER_SECOND
                    ) * 1_000_000d
            ) / 1_000_000d;
            double lipSyncCost = !HEYGEN_AVATAR4_MODEL.equals(avatarModel)
                    && LATENT_SYNC_MODEL.equals(lipSyncModel)
                    ? LATENT_SYNC_TEST_COST_USD
                    : 0.0d;
            double totalCost = Math.round(
                    (avatarCost + lipSyncCost) * 1_000_000d
            ) / 1_000_000d;
            Map<String, Object> costMetadata = new LinkedHashMap<>();
            costMetadata.put("modelApiInteracted", true);
            costMetadata.put("provider", "fal.ai");
            costMetadata.put("model", combinedProviderModel(avatarModel, lipSyncModel));
            costMetadata.put("currency", "USD");
            costMetadata.put("actualTotalCost", totalCost);
            costMetadata.put("totalCost", totalCost);
            costMetadata.put("usage", Map.of(
                    "durationSeconds", durationSeconds,
                    "avatarCost", avatarCost,
                    "lipSyncCost", lipSyncCost
            ));
            return new LinkedHashMap<>(Map.of(
                    "provider", "fal.ai",
                    "avatarModel", avatarModel,
                    "lipSyncModel", lipSyncModel,
                    "lipSyncStatus", "completed",
                    "costMetadata", costMetadata
            ));
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            Map<String, Object> response = objectMapper.readValue(
                    decoded,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            response.putIfAbsent("avatarModel", avatarModel);
            response.putIfAbsent("lipSyncModel", lipSyncModel);
            response.putIfAbsent("lipSyncStatus", "completed");
            return response;
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ai-service returned invalid avatar test metadata.",
                    ex
            );
        }
    }

    private void resetAvatarTest(Map<String, Object> profile) {
        profile.put("avatarTestStatus", "NOT_REQUESTED");
        profile.remove("avatarTestAsset");
        profile.remove("avatarTestUrl");
        profile.remove("avatarTestGeneratedAt");
        profile.remove("avatarTestRequestId");
        profile.remove("avatarTestLipSyncModel");
    }

    private void attachProfile(CreatorScript script, Map<String, Object> sourceAsset, Map<String, Object> profile) {
        Map<String, Object> payload = copyMap(script.getScriptPayload());
        Map<String, Object> creatorContext = copyMap(payload.get("creatorContext"));
        Map<String, Object> metadata = copyMap(creatorContext.get("metadata"));
        Map<String, Object> profileCopy = new LinkedHashMap<>(profile);
        profileCopy.put("sourceAsset", new LinkedHashMap<>(sourceAsset));
        payload.put("founderAvatarProfile", profileCopy);
        payload.put("founderKit", profileCopy);
        payload.put("founderLedHybridEnabled", true);
        creatorContext.put("founderAvatarProfile", profileCopy);
        creatorContext.put("founderKit", profileCopy);
        creatorContext.put("founderLedHybridEnabled", true);
        metadata.put("founderAvatarProfile", profileCopy);
        metadata.put("founderLedHybridEnabled", true);
        creatorContext.put("metadata", metadata);
        payload.put("creatorContext", creatorContext);
        script.setScriptPayload(payload);
        scriptRepository.saveAndFlush(script);
    }

    private Map<String, Object> persistedProfile(CreatorScript script) {
        Map<String, Object> payload = copyMap(script.getScriptPayload());
        Map<String, Object> creatorContext = firstMap(payload.get("creatorContext"));
        return new LinkedHashMap<>(firstMap(
                payload.get("founderAvatarProfile"),
                payload.get("founderKit"),
                creatorContext.get("founderAvatarProfile"),
                creatorContext.get("founderKit")
        ));
    }

    private CreatorScript loadScript(UUID scriptId, String tenantId, String userId) {
        if (scriptId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Script id is required.");
        }
        return scriptRepository
                .findByIdAndTenantIdAndUserId(
                        scriptId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator script was not found."));
    }

    private WebClient aiClient() {
        return webClientBuilder
                .baseUrl(firstText(
                        configuredAiServiceUrl,
                        System.getProperty("dalai.llama.ai-service-url"),
                        System.getenv("DALAI_LLAMA_AI_SERVICE_URL"),
                        System.getenv("DALLAI_LLAMA_AI_SERVICE_URL"),
                        System.getenv("AI_SERVICE_URL"),
                        DEFAULT_AI_SERVICE_URL
                ))
                .build();
    }

    private void applyAuth(HttpHeaders headers) {
        String apiKey = firstText(
                System.getenv("DALAI_LLAMA_AI_SERVICE_API_KEY"),
                System.getenv("DALAI_LLAMA_API_KEY"),
                System.getenv("DALLAI_LLAMA_API_KEY")
        );
        if (apiKey.isBlank()) {
            return;
        }
        String authHeader = firstText(System.getenv("DALAI_LLAMA_AUTH_HEADER"), "Authorization");
        String authPrefix = firstText(System.getenv("DALAI_LLAMA_AUTH_PREFIX"));
        headers.set(authHeader, authPrefix.isBlank() ? apiKey : authPrefix + " " + apiKey);
    }

    private void addPart(MultipartBodyBuilder multipart, String name, Object value) {
        String text = firstText(value);
        if (!text.isBlank()) {
            multipart.part(name, text);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not serialize avatar test metadata.", ex);
        }
    }

    private MediaType mediaType(String value, MediaType fallback) {
        try {
            return MediaType.parseMediaType(firstText(value, fallback.toString()));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String normalizeLipSyncModel(Object value) {
        return switch (firstText(value, LATENT_SYNC_MODEL).trim().toLowerCase(Locale.ROOT)) {
            case AVATAR_NATIVE_MODEL, "native", "none" -> AVATAR_NATIVE_MODEL;
            case MUSE_TALK_MODEL, "musetalk" -> MUSE_TALK_MODEL;
            case "sync_labs" -> "sync_labs";
            case "api_fallback" -> "api_fallback";
            case LATENT_SYNC_MODEL, "latentsync" -> LATENT_SYNC_MODEL;
            default -> LATENT_SYNC_MODEL;
        };
    }

    private String normalizeAvatarModel(Object value) {
        String normalized = firstText(value, HEYGEN_AVATAR4_MODEL)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replaceAll("[^a-z0-9_]+", "_");
        if (normalized.contains("heygen") && (normalized.contains("avatar4") || normalized.contains("avatar_4"))) {
            return HEYGEN_AVATAR4_MODEL;
        }
        if (normalized.contains("happy_horse") || normalized.contains("happyhorse")) {
            return HAPPY_HORSE_MODEL;
        }
        return HEYGEN_AVATAR4_MODEL;
    }

    private String normalizeAvatarResolution(String value, String avatarModel) {
        String normalized = firstText(
                value,
                HEYGEN_AVATAR4_MODEL.equals(avatarModel) ? "720p" : "1080p"
        ).toLowerCase(Locale.ROOT);
        if (HEYGEN_AVATAR4_MODEL.equals(avatarModel)
                && List.of("360p", "480p", "540p", "720p", "1080p").contains(normalized)) {
            return normalized;
        }
        return "720p".equals(normalized) ? "720p" : "1080p";
    }

    private String normalizeTalkingStyle(String value) {
        return "expressive".equalsIgnoreCase(firstText(value)) ? "expressive" : "stable";
    }

    private String combinedProviderModel(String avatarModel, String lipSyncModel) {
        if (HEYGEN_AVATAR4_MODEL.equals(normalizeAvatarModel(avatarModel))) {
            return HEYGEN_AVATAR4_ENDPOINT;
        }
        return switch (normalizeLipSyncModel(lipSyncModel)) {
            case MUSE_TALK_MODEL -> HAPPY_HORSE_ENDPOINT + "+" + MUSE_TALK_ENDPOINT;
            case "sync_labs" -> HAPPY_HORSE_ENDPOINT + "+sync_labs";
            case "api_fallback" -> HAPPY_HORSE_ENDPOINT + "+api_fallback";
            default -> COMBINED_PROVIDER_MODEL;
        };
    }

    private String normalizePortraitMode(String value, MultipartFile file) {
        String normalized = firstText(value, file == null ? "extract" : "upload")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return normalized.contains("upload") || normalized.contains("image") ? "upload" : "extract";
    }

    private String imageContentTypeForFilename(String filename) {
        String normalized = firstText(filename).toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (normalized.endsWith(".webp")) return "image/webp";
        return MediaType.IMAGE_JPEG_VALUE;
    }

    private boolean isSupportedImageFilename(String filename) {
        String normalized = firstText(filename).toLowerCase(Locale.ROOT);
        return normalized.endsWith(".jpg")
                || normalized.endsWith(".jpeg")
                || normalized.endsWith(".png")
                || normalized.endsWith(".webp");
    }

    private String imageFileExtension(String contentType) {
        String normalized = firstText(contentType).toLowerCase(Locale.ROOT);
        if (normalized.contains("png")) return "png";
        if (normalized.contains("webp")) return "webp";
        return "jpg";
    }

    private String mediaFileExtension(String contentType, String objectKey) {
        String normalized = firstText(contentType).toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("matroska")) return ".mkv";
        String key = firstText(objectKey).toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            return key.substring(dot);
        }
        return ".mp4";
    }

    private int clampInt(Object value, int minimum, int maximum, int fallback) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(firstText(value))));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        try {
            return Double.parseDouble(firstText(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = firstText(value).toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "on").contains(normalized)) return true;
        if (List.of("false", "0", "no", "off").contains(normalized)) return false;
        return fallback;
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private String tail(String value, int maximum) {
        String text = firstText(value);
        return text.length() <= maximum ? text : text.substring(text.length() - maximum);
    }

    private Map<String, Object> copyMap(Object value) {
        return new LinkedHashMap<>(firstMap(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMap(Object... values) {
        for (Object value : values) {
            if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, item) -> result.put(String.valueOf(key), item));
                return result;
            }
        }
        return Map.of();
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (!text.isBlank() && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return "";
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
