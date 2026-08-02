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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class FounderAvatarPreviewService {

    private static final Logger log = LoggerFactory.getLogger(FounderAvatarPreviewService.class);
    private static final String DEFAULT_AI_SERVICE_URL = "http://ai-service.apps.svc.cluster.local:8601";
    private static final String DEFAULT_SCENE_FILE_PATH = "/creator/avatar/scenes/files";
    private static final String ASSET_TYPE_FOUNDER_AVATAR_PREVIEW = "FOUNDER_AVATAR_PREVIEW_VIDEO";
    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    private static final String AVATAR_METADATA_HEADER = "X-Dalai-Avatar-Metadata";

    private final CreatorScriptRepository scriptRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final String configuredAiServiceUrl;
    private final long timeoutMs;

    private record StreamedPreview(
            AssetStorageService.StoredObject storedObject,
            Map<String, Object> providerResponse
    ) {
    }

    public FounderAvatarPreviewService(
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
    public Map<String, Object> generatePreview(
            UUID scriptId,
            Map<String, Object> requestBody,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> request = copyMap(requestBody);
        Map<String, Object> profile = persistedProfile(script);
        if (profile.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload the creator source video before creating a lip-sync preview.");
        }
        if (!booleanValue(profile.get("consentConfirmed"), false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Creator consent is required before lip-sync processing.");
        }
        if (!"APPROVED".equalsIgnoreCase(firstText(profile.get("voiceApprovalStatus")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approve the cloned voice before creating a lip-sync preview.");
        }

        Map<String, Object> sourceAsset = firstMap(profile.get("sourceAsset"));
        Map<String, Object> voiceAsset = firstMap(
                profile.get("voicePreviewAsset"),
                profile.get("exactFounderAudioAsset"),
                profile.get("finalFounderAudioAsset")
        );
        String sourceObjectKey = firstText(sourceAsset.get("objectKey"), profile.get("sourceObjectKey"));
        String voiceObjectKey = firstText(voiceAsset.get("objectKey"));
        if (sourceObjectKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The uploaded creator video is not available in storage.");
        }
        if (voiceObjectKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The approved cloned-voice preview is not available in storage.");
        }

        String requestId = firstText(request.get("requestId"), "avatar-preview-" + UUID.randomUUID());
        String dialogue = firstText(
                request.get("previewText"),
                request.get("dialogueText"),
                profile.get("voicePreviewText"),
                profile.get("spokenText")
        );
        String language = firstText(profile.get("language"), "English");
        String languageCode = firstText(profile.get("languageCode"), "en-IN");
        int durationSeconds = clampInt(request.get("durationSeconds"), 3, 8, 8);
        String aspectRatio = firstText(request.get("aspectRatio"), "9:16");
        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(profile.get("localModels")));
        localModels.put("talkingAvatarModel", "source_video");
        localModels.put("lipSyncModel", "fal_latentsync");

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                null,
                null
        );
        creatorAiService.assertWalletBalanceForModelRun("FOUNDER_AVATAR_PREVIEW", usageContext);
        long startedNanos = System.nanoTime();
        StreamedPreview generatedPreview = generateFalLipSyncPreview(
                script,
                profile,
                localModels,
                sourceAsset,
                voiceAsset,
                sourceObjectKey,
                voiceObjectKey,
                requestId,
                dialogue,
                language,
                languageCode,
                durationSeconds,
                aspectRatio
        );
        Map<String, Object> providerResponse = generatedPreview.providerResponse();
        Map<String, Object> costMetadata = firstMap(providerResponse.get("costMetadata"));
        creatorAiService.publishProviderUsageDebit(
                "FOUNDER_AVATAR_PREVIEW",
                firstText(providerResponse.get("provider"), "fal.ai"),
                firstText(providerResponse.get("lipSyncModel"), "fal_latentsync"),
                costMetadata,
                usageContext,
                "Generated founder lip-sync approval preview"
        );

        Map<String, Object> previewAsset = storePreviewAsset(
                script,
                requestId,
                generatedPreview.storedObject(),
                providerResponse,
                costMetadata,
                sourceObjectKey,
                voiceObjectKey
        );
        String fingerprint = stableFingerprint(sourceObjectKey, voiceObjectKey, "fal_latentsync");
        profile.put("localModels", localModels);
        profile.put("avatarPreviewAsset", previewAsset);
        profile.put("avatarPreviewUrl", firstText(previewAsset.get("assetUrl")));
        profile.put("avatarPreviewStatus", "PREVIEW_READY");
        profile.put("avatarPreviewGeneratedAt", OffsetDateTime.now().toString());
        profile.put("avatarPreviewDialogue", dialogue);
        profile.put("avatarPreviewLanguage", language);
        profile.put("avatarPreviewLanguageCode", languageCode);
        profile.put("avatarPreviewFingerprint", fingerprint);
        profile.put("avatarPreviewRequestId", requestId);
        profile.put("lipSyncProvider", "fal.ai");
        profile.put("lipSyncModel", "fal_latentsync");
        profile.remove("avatarPreviewApprovedAt");
        profile.remove("avatarPreviewApprovedBy");
        attachProfile(script, sourceAsset, profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "PREVIEW_READY");
        response.put("requestId", requestId);
        response.put("previewAsset", previewAsset);
        response.put("lipSyncProvider", "fal.ai");
        response.put("lipSyncModel", firstText(providerResponse.get("lipSyncModel"), "fal_latentsync"));
        response.put("lipSyncStatus", firstText(providerResponse.get("lipSyncStatus"), "completed"));
        response.put("founderAvatarProfile", profile);
        log.info(
                "Founder avatar preview completed requestId={} scriptId={} elapsedMs={} bytes={} lipSyncModel={}",
                requestId,
                scriptId,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                generatedPreview.storedObject().sizeBytes(),
                response.get("lipSyncModel")
        );
        return response;
    }

    @Transactional
    public Map<String, Object> updateApproval(
            UUID scriptId,
            Map<String, Object> requestBody,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> profile = persistedProfile(script);
        String decision = firstText(firstMap(requestBody).get("decision"), "APPROVE").toUpperCase(Locale.ROOT);
        if (!List.of("APPROVE", "REJECT", "RESET", "RETRY").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar decision must be APPROVE, REJECT, RETRY, or RESET.");
        }
        Map<String, Object> avatarTestAsset = firstMap(profile.get("avatarTestAsset"));
        boolean avatarTestReady = !avatarTestAsset.isEmpty()
                && ("TEST_READY".equalsIgnoreCase(firstText(profile.get("avatarTestStatus")))
                || "APPROVED".equalsIgnoreCase(firstText(profile.get("avatarTestStatus"))));
        Map<String, Object> previewAsset = firstMap(profile.get("avatarPreviewAsset"));
        boolean previewReady = !previewAsset.isEmpty()
                && ("PREVIEW_READY".equalsIgnoreCase(firstText(profile.get("avatarPreviewStatus")))
                || "APPROVED".equalsIgnoreCase(firstText(profile.get("avatarPreviewStatus"))));
        if ("APPROVE".equals(decision) && !avatarTestReady && !previewReady) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Create the portrait avatar quality test or original-video lip-sync preview before approving the avatar."
            );
        }

        if ("APPROVE".equals(decision)) {
            if (avatarTestReady) {
                profile.put("avatarPreviewAsset", avatarTestAsset);
                profile.put("avatarPreviewUrl", firstText(
                        profile.get("avatarTestUrl"),
                        avatarTestAsset.get("assetUrl"),
                        avatarTestAsset.get("signedUrl"),
                        avatarTestAsset.get("publicUrl")
                ));
                profile.put("avatarPreviewSource", "portrait_avatar_quality_test");
                profile.put("avatarTestStatus", "APPROVED");
                Map<String, Object> testedModels = firstMap(
                        profile.get("avatarTestLocalModels"),
                        profile.get("localModels")
                );
                if (!testedModels.isEmpty()) {
                    profile.put("localModels", testedModels);
                }
            }
            profile.put("avatarPreviewStatus", "APPROVED");
            profile.put("avatarPreviewApprovedAt", OffsetDateTime.now().toString());
            profile.put("avatarPreviewApprovedBy", defaultString(userId, "anonymous"));
        } else {
            profile.put("avatarPreviewStatus", "RESET".equals(decision) ? "NOT_REQUESTED" : "REJECTED");
            if (!avatarTestAsset.isEmpty()) {
                profile.put("avatarTestStatus", "RESET".equals(decision) ? "NOT_REQUESTED" : "REJECTED");
            }
            profile.remove("avatarPreviewApprovedAt");
            profile.remove("avatarPreviewApprovedBy");
        }
        attachProfile(script, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", profile.get("avatarPreviewStatus"));
        response.put("founderAvatarProfile", profile);
        return response;
    }

    private StreamedPreview generateFalLipSyncPreview(
            CreatorScript script,
            Map<String, Object> profile,
            Map<String, Object> localModels,
            Map<String, Object> sourceAsset,
            Map<String, Object> voiceAsset,
            String sourceObjectKey,
            String voiceObjectKey,
            String requestId,
            String dialogue,
            String language,
            String languageCode,
            int durationSeconds,
            String aspectRatio
    ) {
        String sourceBucket = firstText(sourceAsset.get("bucket"), assetStorageService.creatorAssetsBucket());
        String voiceBucket = firstText(voiceAsset.get("bucket"), assetStorageService.creatorAssetsBucket());
        String previewObjectKey = "screenplay-videos/%s/founder-avatar/previews/%s.mp4".formatted(
                script.getId(),
                requestId
        );
        try (
                AssetStorageService.StreamedObject source = assetStorageService.openObjectStream(sourceBucket, sourceObjectKey);
                AssetStorageService.StreamedObject voice = assetStorageService.openObjectStream(voiceBucket, voiceObjectKey)
        ) {
            MultipartBodyBuilder multipart = new MultipartBodyBuilder();
            multipart.part(
                            "sourceVideo",
                            streamResource(source, firstText(sourceAsset.get("originalFilename"), "creator-source.mp4"))
                    )
                    .filename(firstText(sourceAsset.get("originalFilename"), "creator-source.mp4"))
                    .contentType(mediaType(source.contentType(), MediaType.APPLICATION_OCTET_STREAM));
            multipart.part(
                            "dialogueAudio",
                            streamResource(voice, "approved-cloned-voice.wav")
                    )
                    .filename("approved-cloned-voice.wav")
                    .contentType(mediaType(voice.contentType(), MediaType.APPLICATION_OCTET_STREAM));
            addPart(multipart, "runId", requestId);
            addPart(multipart, "scriptId", script.getId());
            addPart(multipart, "sceneId", "founder-avatar-preview");
            addPart(multipart, "sceneNumber", 1);
            addPart(multipart, "provider", "dalai_llama");
            addPart(multipart, "model", "source_video");
            addPart(multipart, "durationSeconds", durationSeconds);
            addPart(multipart, "aspectRatio", aspectRatio);
            addPart(multipart, "prompt", "Natural founder talking-head approval preview using the uploaded creator video.");
            addPart(multipart, "dialogueScript", dialogue);
            addPart(multipart, "exactDialogue", dialogue);
            addPart(multipart, "language", language);
            addPart(multipart, "languageCode", languageCode);
            addPart(multipart, "generationMode", "talking_head");
            addPart(multipart, "founderAvatarProfileJson", json(profile));
            addPart(multipart, "localModelsJson", json(localModels));
            addPart(multipart, "voiceModel", firstText(localModels.get("voiceModel"), "client_rvc_english"));
            addPart(multipart, "talkingAvatarModel", "source_video");
            addPart(multipart, "imageModel", firstText(localModels.get("imageModel"), "gemini_storyboard"));
            addPart(multipart, "lightingModel", firstText(localModels.get("lightingModel"), "ic_lightning"));
            addPart(multipart, "lipSyncModel", "fal_latentsync");
            addPart(multipart, "videoModel", firstText(localModels.get("videoModel"), "fal_seedance"));
            addPart(multipart, "gpuProfile", firstText(localModels.get("gpuProfile"), "rtx_4060_8gb"));
            addPart(multipart, "consentConfirmed", true);
            addPart(multipart, "manualApprovalRequiredForFallback", true);
            addPart(multipart, "fallbackProvider", "synthesia");
            addPart(multipart, "productionEnhancementEnabled", false);

            log.info(
                    "Founder avatar preview handoff started requestId={} scriptId={} sourceBytes={} voiceBytes={} lipSyncModel=fal_latentsync",
                    requestId,
                    script.getId(),
                    source.sizeBytes(),
                    voice.sizeBytes()
            );
            Path responsePath = Files.createTempFile("founder-avatar-preview-", ".mp4");
            try {
                StreamedPreview preview = aiClient()
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
                                                "ai-service rejected avatar preview: " + firstText(body, response.statusCode())
                                        )));
                            }
                            String contentType = response.headers().contentType()
                                    .map(MediaType::toString)
                                    .orElse(MediaType.valueOf("video/mp4").toString());
                            Map<String, Object> providerResponse = decodeProviderResponse(
                                    response.headers().asHttpHeaders()
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
                                            throw new IllegalStateException("ai-service returned an empty avatar preview stream.");
                                        }
                                        AssetStorageService.StoredObject stored =
                                                assetStorageService.uploadCreatorAssetFromPath(
                                                        previewObjectKey,
                                                        responsePath,
                                                        contentType,
                                                        SIGNED_URL_TTL
                                                );
                                        log.info(
                                                "Founder avatar preview stream saved requestId={} bytes={} objectKey={} falRequestId={}",
                                                requestId,
                                                stored.sizeBytes(),
                                                stored.objectKey(),
                                                firstText(providerResponse.get("falRequestId"))
                                        );
                                        return new StreamedPreview(stored, providerResponse);
                                    }).subscribeOn(Schedulers.boundedElastic()));
                        })
                        .block(Duration.ofMillis(timeoutMs));
                if (preview == null) {
                    throw new IllegalStateException("ai-service returned no avatar preview stream.");
                }
                return preview;
            } finally {
                Files.deleteIfExists(responsePath);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not stream the creator video and approved voice from storage.",
                    ex
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error(
                    "Founder avatar preview handoff failed requestId={} scriptId={} errorType={} errorMessage={}",
                    requestId,
                    script.getId(),
                    ex.getClass().getSimpleName(),
                    firstText(ex.getMessage(), "unknown")
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "fal.ai lip-sync preview failed before approval. Reference: " + requestId,
                    ex
            );
        }
    }

    private Map<String, Object> storePreviewAsset(
            CreatorScript script,
            String requestId,
            AssetStorageService.StoredObject stored,
            Map<String, Object> providerResponse,
            Map<String, Object> costMetadata,
            String sourceObjectKey,
            String voiceObjectKey
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        metadata.put("requestId", requestId);
        metadata.put("assetRole", "founder_avatar_lipsync_preview");
        metadata.put("provider", firstText(providerResponse.get("provider"), "fal.ai"));
        metadata.put("model", firstText(providerResponse.get("lipSyncModel"), "fal_latentsync"));
        metadata.put("lipSyncStatus", firstText(providerResponse.get("lipSyncStatus"), "completed"));
        metadata.put("sourceObjectKey", sourceObjectKey);
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
                .assetType(ASSET_TYPE_FOUNDER_AVATAR_PREVIEW)
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
        asset.put("assetType", ASSET_TYPE_FOUNDER_AVATAR_PREVIEW);
        asset.put("assetRole", "founder_avatar_lipsync_preview");
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

    private Map<String, Object> decodeProviderResponse(HttpHeaders headers) {
        String encoded = firstText(headers.getFirst(AVATAR_METADATA_HEADER));
        if (encoded.isBlank()) {
            return new LinkedHashMap<>(Map.of(
                    "provider", "fal.ai",
                    "lipSyncModel", "fal_latentsync",
                    "lipSyncStatus", "completed"
            ));
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            Map<String, Object> response = objectMapper.readValue(
                    decoded,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            response.putIfAbsent("provider", "fal.ai");
            response.putIfAbsent("lipSyncModel", "fal_latentsync");
            response.putIfAbsent("lipSyncStatus", "completed");
            return response;
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ai-service returned invalid avatar stream metadata.",
                    ex
            );
        }
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
            throw new IllegalStateException("Could not serialize avatar preview metadata.", ex);
        }
    }

    private MediaType mediaType(String value, MediaType fallback) {
        try {
            return MediaType.parseMediaType(firstText(value, fallback.toString()));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String stableFingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(firstText(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private int clampInt(Object value, int minimum, int maximum, int fallback) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(firstText(value))));
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
