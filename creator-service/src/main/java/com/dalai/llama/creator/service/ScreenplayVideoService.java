package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.dto.screenplay.ScreenplaySceneView;
import com.dalai.llama.creator.dto.screenplay.SceneViewMapper;
import com.dalai.llama.creator.service.screenplayvideo.AvatarDialogueSyncGateway;
import com.dalai.llama.creator.service.screenplayvideo.MapCoercion;
import com.dalai.llama.creator.service.screenplayvideo.ProviderRequestFactory;
import com.dalai.llama.creator.service.screenplayvideo.SceneChatEditor;
import com.dalai.llama.creator.service.screenplayvideo.SceneEditResult;
import com.dalai.llama.creator.service.screenplayvideo.ShotPlanTagGateway;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;
import com.dalai.llama.creator.domain.entity.CreatorAvatarSceneDialogue;
import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.domain.entity.CreatorStoryboard;
import com.dalai.llama.creator.domain.entity.CreatorStoryboardScene;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorGenerationJobRepository;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardSceneRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ScreenplayVideoService implements ProviderRequestFactory {

    private static final Logger log = LoggerFactory.getLogger(ScreenplayVideoService.class);

    private static final String JOB_SCREENPLAY_VIDEO_GENERATE = "SCREENPLAY_VIDEO_GENERATE";
    private static final String JOB_SCREENPLAY_VIDEO_SCENE_CHAT = "SCREENPLAY_VIDEO_SCENE_CHAT";
    private static final String JOB_SCREENPLAY_VIDEO_SCENE_REGENERATE = "SCREENPLAY_VIDEO_SCENE_REGENERATE";
    private static final String JOB_SCREENPLAY_VIDEO_SCENE_VOICE = "SCREENPLAY_VIDEO_SCENE_VOICE";
    private static final String JOB_SCREENPLAY_VIDEO_SCENE_VOICE_APPROVAL = "SCREENPLAY_VIDEO_SCENE_VOICE_APPROVAL";
    private static final String JOB_SCREENPLAY_VIDEO_SCENE_PORTRAIT = "SCREENPLAY_VIDEO_SCENE_PORTRAIT";
    private static final String JOB_SCREENPLAY_VIDEO_SCENE_IMAGE = "SCREENPLAY_VIDEO_SCENE_IMAGE";
    private static final String JOB_SCREENPLAY_VIDEO_DIALOGUE_COMBINE = "SCREENPLAY_VIDEO_DIALOGUE_COMBINE";
    private static final String JOB_SCREENPLAY_VIDEO_FINAL_RENDER = "SCREENPLAY_VIDEO_FINAL_RENDER";
    private static final String JOB_SCREENPLAY_VIDEO_AUDIO_PACK = "SCREENPLAY_VIDEO_AUDIO_PACK";
    private static final String PROMPT_SCREENPLAY_DIALOGUE_LOCALIZE = "SCREENPLAY_DIALOGUE_LOCALIZE";
    private static final String ASSET_TYPE_SCREENPLAY_AUDIO_VOICEOVER = "SCREENPLAY_AUDIO_VOICEOVER";
    private static final String ASSET_TYPE_SCREENPLAY_AUDIO_MUSIC_BED = "SCREENPLAY_AUDIO_MUSIC_BED";
    private static final String ASSET_TYPE_SCREENPLAY_AUDIO = "SCREENPLAY_AUDIO";
    private static final String ASSET_TYPE_SCREENPLAY_REFERENCE_IMAGE = "SCREENPLAY_REFERENCE_IMAGE";
    private static final String ASSET_TYPE_FOUNDER_AVATAR_SOURCE_VIDEO = "FOUNDER_AVATAR_SOURCE_VIDEO";
    static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    static final long MEDIA_URL_RENEWAL_SECONDS = 6 * 60 * 60;
    private final CreatorScriptRepository scriptRepository;
    private final CreatorScriptShotRepository scriptShotRepository;
    private final CreatorStoryboardRepository storyboardRepository;
    private final CreatorStoryboardSceneRepository storyboardSceneRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final CreatorAssetRepository assetRepository;
    private final CreatorGenerationJobRepository generationJobRepository;
    private final CreatorPromptRunRepository promptRunRepository;
    private final GenerationJobService generationJobService;
    private final CreatorAiService creatorAiService;
    private final ScreenplayVideoProviderGenerationService providerGenerationService;
    private final AvatarSceneDialogueService avatarSceneDialogueService;
    private final GoogleChirpVoiceGenerationService voiceGenerationService;
    private final GoogleLyriaMusicGenerationService musicGenerationService;
    private final AssetStorageService assetStorageService;
    private final BillingWalletService billingWalletService;
    private final CreatorScreenplaySceneAssetService sceneAssetService;
    private final ShotPlanTagGateway shotPlanTagGateway;
    private final SceneChatEditor sceneChatEditor;
    private final AvatarDialogueSyncGateway avatarDialogueSyncGateway;
    private final ObjectMapper objectMapper;
    private final BigDecimal aiShortStarterPriceInr;
    private final BigDecimal packageUsdInrRate;
    private final BigDecimal usageMarkupPercent;
    private final BigDecimal videoUsageMarkupPercent;

    public ScreenplayVideoService(
            CreatorScriptRepository scriptRepository,
            CreatorScriptShotRepository scriptShotRepository,
            CreatorStoryboardRepository storyboardRepository,
            CreatorStoryboardSceneRepository storyboardSceneRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            CreatorAssetRepository assetRepository,
            CreatorGenerationJobRepository generationJobRepository,
            CreatorPromptRunRepository promptRunRepository,
            GenerationJobService generationJobService,
            CreatorAiService creatorAiService,
            ScreenplayVideoProviderGenerationService providerGenerationService,
            AvatarSceneDialogueService avatarSceneDialogueService,
            GoogleChirpVoiceGenerationService voiceGenerationService,
            GoogleLyriaMusicGenerationService musicGenerationService,
            AssetStorageService assetStorageService,
            BillingWalletService billingWalletService,
            CreatorScreenplaySceneAssetService sceneAssetService,
            ShotPlanTagGateway shotPlanTagGateway,
            SceneChatEditor sceneChatEditor,
            AvatarDialogueSyncGateway avatarDialogueSyncGateway,
            ObjectMapper objectMapper,
            @Value("${creator.video-packages.ai-short-starter-price-inr:5999}") BigDecimal aiShortStarterPriceInr,
            @Value("${creator.video-packages.usd-inr-rate:95}") BigDecimal packageUsdInrRate,
            @Value("${creator.ai.billing.usage-markup-percent:85}") BigDecimal usageMarkupPercent,
            @Value("${creator.ai.billing.video-usage-markup-percent:20}") BigDecimal videoUsageMarkupPercent
    ) {
        this.scriptRepository = scriptRepository;
        this.scriptShotRepository = scriptShotRepository;
        this.storyboardRepository = storyboardRepository;
        this.storyboardSceneRepository = storyboardSceneRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.assetRepository = assetRepository;
        this.generationJobRepository = generationJobRepository;
        this.promptRunRepository = promptRunRepository;
        this.generationJobService = generationJobService;
        this.creatorAiService = creatorAiService;
        this.providerGenerationService = providerGenerationService;
        this.avatarSceneDialogueService = avatarSceneDialogueService;
        this.voiceGenerationService = voiceGenerationService;
        this.musicGenerationService = musicGenerationService;
        this.assetStorageService = assetStorageService;
        this.billingWalletService = billingWalletService;
        this.sceneAssetService = sceneAssetService;
        this.shotPlanTagGateway = shotPlanTagGateway;
        this.sceneChatEditor = sceneChatEditor;
        this.avatarDialogueSyncGateway = avatarDialogueSyncGateway;
        this.objectMapper = objectMapper;
        this.aiShortStarterPriceInr = aiShortStarterPriceInr == null ? BigDecimal.valueOf(5999) : aiShortStarterPriceInr;
        this.packageUsdInrRate = packageUsdInrRate == null ? BigDecimal.valueOf(95) : packageUsdInrRate;
        this.usageMarkupPercent = usageMarkupPercent == null ? BigDecimal.valueOf(85) : usageMarkupPercent;
        this.videoUsageMarkupPercent = videoUsageMarkupPercent == null ? BigDecimal.valueOf(20) : videoUsageMarkupPercent;
    }

    @Transactional
    public Map<String, Object> uploadReferenceImage(
            UUID scriptId,
            MultipartFile file,
            String details,
            boolean enhanceScreenplay,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a product or style reference image.");
        }
        String contentType = firstText(file.getContentType(), "application/octet-stream");
        if (!isImageContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reference asset must be a JPG, PNG, WebP, AVIF, or GIF image.");
        }

        String objectKey = "screenplay-videos/%s/reference-images/%s-%s.%s".formatted(
                script.getId(),
                UUID.randomUUID(),
                safeSlug(firstText(file.getOriginalFilename(), "reference-image")),
                imageFileExtension(contentType)
        );

        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded reference image.", ex);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        metadata.put("source", "user_upload");
        metadata.put("referenceRole", "product_visual_anchor");
        metadata.put("assetRole", "product_visual_anchor");
        metadata.put("details", firstText(details));
        metadata.put("enhanceScreenplay", enhanceScreenplay);
        metadata.put("originalFilename", firstText(file.getOriginalFilename(), "reference-image"));
        metadata.put("storageStatus", "SAVED_TO_MINIO");

        CreatorAsset asset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_SCREENPLAY_REFERENCE_IMAGE)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("assetId", asset.getId().toString());
        response.put("id", asset.getId().toString());
        response.put("scriptId", script.getId().toString());
        response.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        response.put("assetType", ASSET_TYPE_SCREENPLAY_REFERENCE_IMAGE);
        response.put("referenceRole", "product_visual_anchor");
        response.put("assetRole", "product_visual_anchor");
        response.put("bucket", stored.bucket());
        response.put("objectKey", stored.objectKey());
        response.put("contentType", stored.contentType());
        response.put("sizeBytes", stored.sizeBytes());
        response.put("publicUrl", stored.signedUrl());
        response.put("signedUrl", stored.signedUrl());
        response.put("assetUrl", stored.signedUrl());
        response.put("details", firstText(details));
        response.put("enhanceScreenplay", enhanceScreenplay);
        response.put("metadata", metadata);
        attachReferenceImageToScript(script, response, details, enhanceScreenplay);
        response.put("scriptReferenceUpdated", true);
        return response;
    }

    @Transactional
    public Map<String, Object> uploadFounderAvatarSource(
            UUID scriptId,
            MultipartFile file,
            String details,
            String providerMode,
            String synthesiaAvatarId,
            String synthesiaVoiceId,
            String localVoiceModel,
            String voiceProfileId,
            String localTalkingAvatarModel,
            String localLipSyncModel,
            String localImageModel,
            String localVideoModel,
            String referenceTranscript,
            String previewText,
            String spokenText,
            String pronunciationGuide,
            String elevenLabsVoiceId,
            String sarvamVoiceId,
            boolean productionEnhancementEnabled,
            String productionEnhancementPrompt,
            boolean consentConfirmed,
            String language,
            String languageCode,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        if (!consentConfirmed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Founder consent is required before uploading media for avatar or voice cloning."
            );
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the founder reference video first.");
        }
        String contentType = firstText(file.getContentType(), "application/octet-stream");
        if (!isVideoContentType(contentType)) {
            contentType = videoContentTypeForFilename(file.getOriginalFilename());
        }
        if (!isVideoContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Founder avatar source must be a video file.");
        }

        String safeProviderMode = normalizeAvatarProviderMode(providerMode);
        String objectKey = "screenplay-videos/%s/founder-avatar/%s-%s.%s".formatted(
                script.getId(),
                UUID.randomUUID(),
                safeSlug(firstText(file.getOriginalFilename(), "founder-avatar-source")),
                videoFileExtension(contentType)
        );

        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded founder video.", ex);
        }

        String assetFingerprint = stableFingerprint(script.getId().toString(), stored.objectKey(), String.valueOf(stored.sizeBytes()));
        String avatarId = firstText(synthesiaAvatarId, "founder-avatar-" + assetFingerprint.substring(0, 16));
        String voiceId = firstText(synthesiaVoiceId, "founder-voice-" + assetFingerprint.substring(0, 16));
        Map<String, Object> localModels = founderLocalModels(localVoiceModel, localTalkingAvatarModel, localLipSyncModel, localImageModel, localVideoModel);
        String selectedVoiceProfileId = "client_rvc_english".equals(normalizeLocalVoiceModel(localVoiceModel))
                ? firstText(voiceProfileId, "founder_female_v1")
                : firstText(voiceProfileId);
        if (!selectedVoiceProfileId.isBlank()) {
            localModels.put("voiceProfileId", selectedVoiceProfileId);
        }
        Map<String, Object> embeddings = founderEmbeddingIds(assetFingerprint);
        String consentConfirmedAt = OffsetDateTime.now().toString();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("providerMode", safeProviderMode);
        profile.put("avatarProviderMode", safeProviderMode);
        profile.put("avatarProvider", safeProviderMode);
        profile.put("avatarId", avatarId);
        profile.put("voiceId", voiceId);
        profile.put("synthesiaAvatarId", firstText(synthesiaAvatarId));
        profile.put("synthesiaVoiceId", firstText(synthesiaVoiceId));
        profile.put("elevenLabsVoiceId", firstText(elevenLabsVoiceId));
        profile.put("sarvamVoiceId", firstText(sarvamVoiceId));
        profile.put("portraitEmbeddingId", embeddings.get("portraitEmbeddingId"));
        profile.put("facialFeatureEmbeddingId", embeddings.get("facialFeatureEmbeddingId"));
        profile.put("voiceEmbeddingId", embeddings.get("voiceEmbeddingId"));
        profile.put("voiceProfileId", selectedVoiceProfileId);
        profile.put("consentConfirmed", consentConfirmed);
        profile.put("consentConfirmedAt", consentConfirmedAt);
        profile.put("consentVersion", "founder-avatar-v1");
        profile.put("consentScopes", List.of("avatar_clone", "voice_clone", "selected_provider_processing"));
        profile.put("manualApprovalRequiredForFallback", true);
        String profileLanguage = firstText(language, script.getDialogueLanguage(), "Hinglish");
        profile.put("language", profileLanguage);
        profile.put("languageCode", firstText(languageCode, languageCodeFor(profileLanguage), "hi-IN"));
        profile.put("sourceAssetId", null);
        profile.put("sourceObjectKey", stored.objectKey());
        profile.put("sourceContentType", stored.contentType());
        profile.put("sourceSizeBytes", stored.sizeBytes());
        profile.put("sourceUrl", stored.signedUrl());
        profile.put("details", firstText(details));
        profile.put("referenceTranscript", firstText(referenceTranscript));
        profile.put("voicePreviewText", firstText(previewText));
        profile.put("spokenText", firstText(spokenText));
        profile.put("pronunciationGuide", firstText(pronunciationGuide));
        profile.put("voiceApprovalStatus", "NOT_REQUESTED");
        profile.put("voicePreviewAttempts", 0);
        profile.put("avatarPreviewStatus", "NOT_REQUESTED");
        profile.put("productionEnhancementEnabled", productionEnhancementEnabled);
        profile.put("productionEnhancementPrompt", firstText(productionEnhancementPrompt, details));
        profile.put("localModels", localModels);
        profile.put("openSourceModelPriority", List.of(
                firstText(localModels.get("voiceModel")),
                firstText(localModels.get("talkingAvatarModel")),
                firstText(localModels.get("lipSyncModel")),
                firstText(localModels.get("imageModel")),
                firstText(localModels.get("videoModel"))
        ));
        profile.put("createdAt", OffsetDateTime.now().toString());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        metadata.put("source", "founder_upload");
        metadata.put("assetRole", "founder_avatar_source");
        metadata.put("referenceRole", "founder_avatar_source");
        metadata.put("details", firstText(details));
        metadata.put("productionEnhancementEnabled", productionEnhancementEnabled);
        metadata.put("productionEnhancementPrompt", firstText(productionEnhancementPrompt, details));
        metadata.put("providerMode", safeProviderMode);
        metadata.put("referenceTranscriptProvided", !firstText(referenceTranscript).isBlank());
        metadata.put("voiceModel", firstText(localModels.get("voiceModel")));
        metadata.put("voiceProfileId", selectedVoiceProfileId);
        metadata.put("consentConfirmed", consentConfirmed);
        metadata.put("consentConfirmedAt", consentConfirmedAt);
        metadata.put("consentVersion", "founder-avatar-v1");
        metadata.put("consentScopes", List.of("avatar_clone", "voice_clone", "selected_provider_processing"));
        metadata.put("localModels", localModels);
        metadata.put("embeddingIds", embeddings);
        metadata.put("originalFilename", firstText(file.getOriginalFilename(), "founder-avatar-source"));
        metadata.put("storageStatus", "SAVED_TO_MINIO");

        CreatorAsset asset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_FOUNDER_AVATAR_SOURCE_VIDEO)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        profile.put("sourceAssetId", asset.getId().toString());
        metadata.put("assetId", asset.getId().toString());

        Map<String, Object> sourceAsset = new LinkedHashMap<>();
        sourceAsset.put("assetId", asset.getId().toString());
        sourceAsset.put("id", asset.getId().toString());
        sourceAsset.put("assetType", ASSET_TYPE_FOUNDER_AVATAR_SOURCE_VIDEO);
        sourceAsset.put("assetRole", "founder_avatar_source");
        sourceAsset.put("bucket", stored.bucket());
        sourceAsset.put("objectKey", stored.objectKey());
        sourceAsset.put("contentType", stored.contentType());
        sourceAsset.put("sizeBytes", stored.sizeBytes());
        sourceAsset.put("publicUrl", stored.signedUrl());
        sourceAsset.put("signedUrl", stored.signedUrl());
        sourceAsset.put("assetUrl", stored.signedUrl());
        sourceAsset.put("originalFilename", firstText(file.getOriginalFilename(), "creator-video"));
        profile.put("sourceAsset", sourceAsset);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("assetId", asset.getId().toString());
        response.put("id", asset.getId().toString());
        response.put("scriptId", script.getId().toString());
        response.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        response.put("assetType", ASSET_TYPE_FOUNDER_AVATAR_SOURCE_VIDEO);
        response.put("assetRole", "founder_avatar_source");
        response.put("bucket", stored.bucket());
        response.put("objectKey", stored.objectKey());
        response.put("contentType", stored.contentType());
        response.put("sizeBytes", stored.sizeBytes());
        response.put("publicUrl", stored.signedUrl());
        response.put("signedUrl", stored.signedUrl());
        response.put("assetUrl", stored.signedUrl());
        response.put("details", firstText(details));
        response.put("founderAvatarProfile", profile);
        response.put("avatarProviderMode", safeProviderMode);
        response.put("avatarId", avatarId);
        response.put("voiceId", voiceId);
        response.put("metadata", metadata);

        attachFounderAvatarToScript(script, sourceAsset, profile);
        response.put("scriptFounderProfileUpdated", true);
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listReusableFounderAvatars(
            UUID scriptId,
            String tenantId,
            String userId
    ) {
        loadScript(scriptId, tenantId, userId);
        List<Map<String, Object>> avatars = reusableFounderAvatarEntries(tenantId, userId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scriptId", scriptId.toString());
        response.put("avatars", avatars);
        response.put("count", avatars.size());
        return response;
    }

    @Transactional
    public Map<String, Object> selectReusableFounderAvatar(
            UUID scriptId,
            Map<String, Object> requestBody,
            String tenantId,
            String userId
    ) {
        CreatorScript targetScript = loadScript(scriptId, tenantId, userId);
        Map<String, Object> request = copyMap(requestBody);
        UUID sourceScriptId = uuidValue(request.get("sourceScriptId"));
        String requestedAvatarId = firstText(request.get("avatarId"), request.get("selectionKey"));
        if (sourceScriptId == null && requestedAvatarId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Select a ready avatar. Create and approve a voice clone first."
            );
        }

        Map<String, Object> selected = reusableFounderAvatarEntries(tenantId, userId).stream()
                .filter(entry -> sourceScriptId != null
                        ? sourceScriptId.toString().equals(firstText(entry.get("sourceScriptId")))
                        : requestedAvatarId.equals(firstText(
                                entry.get("avatarId"),
                                entry.get("selectionKey"),
                                entry.get("providerVoiceId")
                        )))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "The selected avatar is not ready. Create and approve its reusable voice clone first."
                ));

        Map<String, Object> profile = copyMap(selected.get("founderAvatarProfile"));
        clearScriptSpecificFounderMedia(profile);
        profile.put("selectedFromScriptId", firstText(selected.get("sourceScriptId")));
        profile.put("selectedAt", OffsetDateTime.now().toString());
        profile.put("selectedBy", defaultString(userId, "anonymous"));
        profile.put("reusableAvatarSelected", true);
        profile.put("voiceApprovalStatus", "APPROVED");
        attachFounderAvatarToScript(targetScript, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SELECTED");
        response.put("scriptId", targetScript.getId().toString());
        response.put("sourceScriptId", selected.get("sourceScriptId"));
        response.put("selectionKey", selected.get("selectionKey"));
        response.put("avatarId", firstText(profile.get("avatarId")));
        response.put("providerVoiceId", firstText(
                profile.get("providerVoiceId"),
                profile.get("minimaxVoiceId"),
                profile.get("elevenLabsVoiceId"),
                profile.get("sarvamVoiceId"),
                profile.get("synthesiaVoiceId")
        ));
        response.put("founderAvatarProfile", profile);
        return response;
    }

    private List<Map<String, Object>> reusableFounderAvatarEntries(String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        List<CreatorScript> scripts = scriptRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(
                safeTenantId,
                safeUserId,
                PageRequest.of(0, 100)
        );
        Map<String, Map<String, Object>> uniqueAvatars = new LinkedHashMap<>();
        for (CreatorScript sourceScript : scripts == null ? List.<CreatorScript>of() : scripts) {
            Map<String, Object> scriptPayload = copyMap(sourceScript.getScriptPayload());
            Map<String, Object> profile = founderAvatarProfile(
                    Map.of(),
                    scriptPayload,
                    firstMap(scriptPayload.get("creatorContext"))
            );
            if (!isReusableFounderAvatarReady(profile)) {
                continue;
            }
            Map<String, Object> sourceAsset = firstMap(profile.get("sourceAsset"));
            String providerVoiceId = firstText(
                    profile.get("providerVoiceId"),
                    profile.get("minimaxVoiceId"),
                    profile.get("elevenLabsVoiceId"),
                    profile.get("sarvamVoiceId"),
                    profile.get("synthesiaVoiceId"),
                    profile.get("voiceProfileId"),
                    profile.get("voiceEmbeddingId")
            );
            String sourceObjectKey = firstText(sourceAsset.get("objectKey"), profile.get("sourceObjectKey"));
            String identityKey = providerVoiceId + "|" + sourceObjectKey;
            if (uniqueAvatars.containsKey(identityKey)) {
                continue;
            }

            String sourceScriptId = sourceScript.getId().toString();
            Map<String, Object> avatar = new LinkedHashMap<>();
            avatar.put("id", sourceScriptId);
            avatar.put("selectionKey", sourceScriptId);
            avatar.put("sourceScriptId", sourceScriptId);
            avatar.put("avatarId", firstText(profile.get("avatarId")));
            avatar.put("providerVoiceId", providerVoiceId);
            avatar.put("voiceModel", firstText(firstMap(profile.get("localModels")).get("voiceModel")));
            avatar.put("voiceApprovalStatus", "APPROVED");
            avatar.put("ready", true);
            avatar.put("name", firstText(
                    sourceAsset.get("originalFilename"),
                    firstMap(sourceAsset.get("metadata")).get("originalFilename"),
                    sourceScript.getTitle(),
                    "Founder avatar"
            ));
            avatar.put("updatedAt", sourceScript.getUpdatedAt() == null ? null : sourceScript.getUpdatedAt().toString());
            avatar.put("createdAt", sourceScript.getCreatedAt() == null ? null : sourceScript.getCreatedAt().toString());
            avatar.put("founderAvatarProfile", profile);
            uniqueAvatars.put(identityKey, avatar);
        }
        return new ArrayList<>(uniqueAvatars.values());
    }

    private boolean isReusableFounderAvatarReady(Map<String, Object> profile) {
        if (profile == null || profile.isEmpty()
                || !booleanValue(profile.get("consentConfirmed"), false)
                || !"APPROVED".equalsIgnoreCase(firstText(profile.get("voiceApprovalStatus")))) {
            return false;
        }
        Map<String, Object> sourceAsset = firstMap(profile.get("sourceAsset"));
        boolean sourceReady = !firstText(
                sourceAsset.get("objectKey"),
                profile.get("sourceObjectKey"),
                profile.get("sourceUrl")
        ).isBlank();
        if (!sourceReady) {
            return false;
        }
        String voiceModel = normalizeLocalVoiceModel(firstText(firstMap(profile.get("localModels")).get("voiceModel")));
        if ("synthesia_managed".equals(voiceModel)) {
            return !firstText(profile.get("synthesiaVoiceId")).isBlank();
        }
        if (!"APPROVED".equalsIgnoreCase(firstText(profile.get("avatarPreviewStatus")))) {
            return false;
        }
        Map<String, Object> localModels = firstMap(profile.get("localModels"));
        String talkingAvatarModel = normalizeLocalTalkingAvatarModel(firstText(localModels.get("talkingAvatarModel")));
        if ("fal_happy_horse_v1_1".equals(talkingAvatarModel)
                && firstText(
                        firstMap(profile.get("avatarPortraitAsset")).get("objectKey"),
                        profile.get("avatarPortraitObjectKey")
                ).isBlank()) {
            return false;
        }
        if ("client_rvc_english".equals(voiceModel)) {
            return !firstText(
                    profile.get("voiceProfileId"),
                    localModels.get("voiceProfileId"),
                    profile.get("voiceEmbeddingId")
            ).isBlank();
        }
        if ("fal_chatterbox_multilingual".equals(voiceModel)) {
            return sourceReady;
        }
        if ("elevenlabs_professional".equals(voiceModel)
                || "fal_elevenlabs_v3".equals(voiceModel)
                || "elevenlabs_v3_voice_clone".equals(voiceModel)) {
            return !firstText(profile.get("elevenLabsVoiceId")).isBlank();
        }
        if ("sarvam_voice_clone".equals(voiceModel)) {
            return !firstText(profile.get("sarvamVoiceId")).isBlank();
        }
        return !firstText(
                profile.get("providerVoiceId"),
                profile.get("minimaxVoiceId"),
                profile.get("customVoiceId")
        ).isBlank();
    }

    private void clearScriptSpecificFounderMedia(Map<String, Object> profile) {
        List.of(
                "avatarScript",
                "avatarScriptOverride",
                "spokenText",
                "spoken_text",
                "fullSpokenText",
                "exactFounderAudioAsset",
                "finalFounderAudioAsset",
                "finalFounderAudioUrl",
                "finalFounderAudioUploadedAt"
        ).forEach(profile::remove);
        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(profile.get("localModels")));
        if ("uploaded_founder_audio".equals(normalizeLocalVoiceModel(firstText(localModels.get("voiceModel"))))) {
            localModels.put("voiceModel", "fal_minimax_voice_clone");
        }
        profile.put("localModels", localModels);
    }

    @Transactional
    public Map<String, Object> prepareFounderEnglishDialogue(
            UUID scriptId,
            Map<String, Object> requestBody,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> request = copyMap(requestBody);
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> profile = founderAvatarProfile(
                request,
                scriptPayload,
                firstMap(scriptPayload.get("creatorContext"))
        );
        if (!booleanValue(profile.get("consentConfirmed"), false)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Confirm founder consent before preparing dialogue for the client voice."
            );
        }

        String sourceLanguage = firstText(
                request.get("sourceDialogueLanguage"),
                script.getDialogueLanguage(),
                scriptPayload.get("dialogueLanguage"),
                "Hinglish"
        );
        String requestedDialogue = firstText(
                request.get("dialogueText"),
                request.get("avatarScript"),
                request.get("spokenText"),
                profile.get("avatarScript"),
                profile.get("spokenText")
        );
        List<Map<String, Object>> sourceDialogueScenes;
        if (!requestedDialogue.isBlank()) {
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", "founder-dialogue");
            scene.put("sceneNumber", 1);
            scene.put("durationSeconds", Math.max(10, Math.min(60, estimatedDialogueSeconds(requestedDialogue))));
            scene.put("dialogueScript", truncate(requestedDialogue, 4800));
            sourceDialogueScenes = List.of(scene);
        } else {
            sourceDialogueScenes = sourceScenes(script, request);
        }
        if (sourceDialogueScenes.isEmpty()
                || sourceDialogueScenes.stream().allMatch(scene -> dialogueTextForScene(scene).isBlank())) {
            String scriptDialogue = audioPackVoiceText(request, Map.of(), script);
            if (scriptDialogue.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "The screenplay does not contain dialogue to translate."
                );
            }
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", "founder-dialogue");
            scene.put("sceneNumber", 1);
            scene.put("durationSeconds", Math.max(10, Math.min(60, estimatedDialogueSeconds(scriptDialogue))));
            scene.put("dialogueScript", scriptDialogue);
            sourceDialogueScenes = List.of(scene);
        }

        List<Map<String, Object>> englishScenes = sameLanguage(sourceLanguage, "English")
                ? sourceDialogueScenes
                : localizeDialogueScenes(
                        script,
                        sourceDialogueScenes,
                        sourceLanguage,
                        "English",
                        "en-IN",
                        null
                );
        StringBuilder englishDialogue = new StringBuilder();
        for (Map<String, Object> scene : englishScenes) {
            String line = dialogueTextForScene(scene);
            if (line.isBlank()) {
                continue;
            }
            if (!englishDialogue.isEmpty()) {
                englishDialogue.append(System.lineSeparator());
            }
            englishDialogue.append(line);
        }
        if (englishDialogue.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "English dialogue preparation returned no spoken lines."
            );
        }

        String preparedDialogue = truncate(englishDialogue.toString(), 12000);
        String profileId = firstText(
                request.get("voiceProfileId"),
                profile.get("voiceProfileId"),
                firstMap(profile.get("localModels")).get("voiceProfileId"),
                "founder_female_v1"
        );
        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(profile.get("localModels")));
        localModels.put("voiceModel", "client_rvc_english");
        localModels.put("voiceProfileId", profileId);
        profile.put("localModels", localModels);
        profile.put("voiceProfileId", profileId);
        profile.put("sourceDialogueLanguage", sourceLanguage);
        profile.put("sourceAvatarScript", requestedDialogue);
        profile.put("avatarScript", preparedDialogue);
        profile.put("spokenText", preparedDialogue);
        profile.put("language", "English");
        profile.put("languageCode", "en-IN");
        profile.put("voiceLanguageMode", "english_indian");
        profile.put("voiceLanguage", "English");
        profile.put("voiceLanguageCode", "en-IN");
        profile.put("voicePreviewText", truncate(preparedDialogue, 240));
        profile.put("voiceApprovalStatus", "NOT_REQUESTED");
        profile.put("voiceTranslationStatus", "COMPLETED");
        profile.put("voiceTranslationPreparedAt", OffsetDateTime.now().toString());
        invalidateAvatarPreview(profile);
        profile.remove("voicePreviewAsset");
        profile.remove("voiceApprovedAt");
        profile.remove("voiceApprovedBy");
        profile.remove("providerVoiceId");
        profile.remove("customVoiceId");
        profile.remove("minimaxVoiceId");
        attachFounderAvatarToScript(script, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "READY");
        response.put("sourceLanguage", sourceLanguage);
        response.put("targetLanguage", "English");
        response.put("languageCode", "en-IN");
        response.put("voiceModel", "client_rvc_english");
        response.put("voiceProfileId", profileId);
        response.put("translatedDialogue", preparedDialogue);
        response.put("scenes", englishScenes);
        response.put("founderAvatarProfile", profile);
        return response;
    }

    @Transactional
    public Map<String, Object> generateFounderVoicePreview(
            UUID scriptId,
            Map<String, Object> requestBody,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> request = copyMap(requestBody);
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> profile = founderAvatarProfile(request, scriptPayload, firstMap(scriptPayload.get("creatorContext")));
        if (!booleanValue(profile.get("consentConfirmed"), false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Founder consent is required before generating a voice preview.");
        }

        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(profile.get("localModels")));
        String voiceModel = normalizeLocalVoiceModel(firstText(
                request.get("voiceModel"),
                request.get("localVoiceModel"),
                localModels.get("voiceModel")
        ));
        if ("fal_minimax_voice_clone".equals(voiceModel)) {
            log.info(
                    "Founder voice provider migrated requestId={} from fal_minimax_voice_clone "
                            + "to fal_chatterbox_multilingual",
                    firstText(request.get("requestId"), "pending")
            );
            voiceModel = "fal_chatterbox_multilingual";
        }
        if ("uploaded_founder_audio".equals(voiceModel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the founder's final audio instead of generating a clone preview.");
        }
        if ("synthesia_managed".equals(voiceModel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Synthesia managed voices are approved in Synthesia. Add the managed voice ID, then approve that provider here.");
        }
        boolean clientRvcVoice = "client_rvc_english".equals(voiceModel);
        String voiceProfileId = clientRvcVoice
                ? firstText(
                        request.get("voiceProfileId"),
                        profile.get("voiceProfileId"),
                        localModels.get("voiceProfileId"),
                        "founder_female_v1"
                )
                : firstText(request.get("voiceProfileId"), profile.get("voiceProfileId"));
        if (clientRvcVoice) {
            localModels.put("voiceProfileId", voiceProfileId);
            profile.put("voiceProfileId", voiceProfileId);
        }

        String referenceTranscript = firstText(request.get("referenceTranscript"), profile.get("referenceTranscript"));
        boolean elevenLabsVoiceModel = "fal_elevenlabs_v3".equals(voiceModel)
                || "elevenlabs_v3_voice_clone".equals(voiceModel)
                || "elevenlabs_professional".equals(voiceModel);
        String elevenLabsVoiceId = elevenLabsVoiceModel
                ? firstText(request.get("elevenLabsVoiceId"), profile.get("elevenLabsVoiceId"))
                : "";
        String sarvamVoiceId = "sarvam_voice_clone".equals(voiceModel)
                ? firstText(request.get("sarvamVoiceId"), profile.get("sarvamVoiceId"))
                : "";
        if ("fal_elevenlabs_v3".equals(voiceModel) && elevenLabsVoiceId.isBlank()) {
            log.info(
                    "Founder voice provider migrated requestId={} from fal_elevenlabs_v3 "
                            + "to fal_chatterbox_multilingual because no ElevenLabs voice ID exists",
                    firstText(request.get("requestId"), "pending")
            );
            voiceModel = "fal_chatterbox_multilingual";
        }
        String requestedProviderVoiceId = "fal_elevenlabs_v3".equals(voiceModel)
                ? elevenLabsVoiceId
                : "elevenlabs_v3_voice_clone".equals(voiceModel)
                ? elevenLabsVoiceId
                : "sarvam_voice_clone".equals(voiceModel)
                ? sarvamVoiceId
                : "fal_minimax_voice_clone".equals(voiceModel) ? firstText(
                        request.get("minimaxVoiceId"),
                        request.get("providerVoiceId"),
                        profile.get("minimaxVoiceId"),
                        profile.get("providerVoiceId")
                ) : "";
        if ("elevenlabs_professional".equals(voiceModel) && elevenLabsVoiceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ElevenLabs Professional requires an existing verified Professional Voice Clone voice ID.");
        }
        String sourceUrl = firstText(profile.get("sourceUrl"), firstMap(profile.get("sourceAsset")).get("assetUrl"));
        if (("fal_minimax_voice_clone".equals(voiceModel)
                || "fal_chatterbox_multilingual".equals(voiceModel)
                || "elevenlabs_v3_voice_clone".equals(voiceModel)
                || "sarvam_voice_clone".equals(voiceModel))
                && requestedProviderVoiceId.isBlank() && elevenLabsVoiceId.isBlank() && sourceUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the founder reference video before generating a voice preview.");
        }

        String captionText = firstText(
                request.get("captionText"),
                request.get("previewText"),
                profile.get("voicePreviewText"),
                "Aap procrastinate isliye nahi karte kyunki aap mein willpower ki kami hai. Aksar dimaag stress se bachne ki koshish kar raha hota hai."
        );
        if (captionText.length() > 240) {
            captionText = captionText.substring(0, 240).trim();
        }
        String pronunciationGuide = firstText(request.get("pronunciationGuide"), profile.get("pronunciationGuide"));
        String spokenText = firstText(request.get("spokenText"), captionText);
        String voiceRequestId = firstText(request.get("requestId"), "voice-" + UUID.randomUUID());
        String voiceLanguage = clientRvcVoice
                ? "English"
                : firstText(request.get("voiceLanguage"), profile.get("voiceLanguage"), profile.get("language"), "Hinglish");
        String voiceLanguageCode = clientRvcVoice
                ? "en-IN"
                : firstText(request.get("voiceLanguageCode"), profile.get("voiceLanguageCode"), profile.get("languageCode"), "hi-IN");
        String referenceLanguage = firstText(request.get("referenceLanguage"), profile.get("referenceLanguage"), "English");
        String minimaxLanguageBoost = firstText(request.get("minimaxLanguageBoost"), profile.get("minimaxLanguageBoost"), "auto");
        String sourceDialogueLanguage = clientRvcVoice
                ? "English"
                : firstText(
                        request.get("sourceDialogueLanguage"),
                        script.getDialogueLanguage(),
                        scriptPayload.get("dialogueLanguage"),
                        "Hinglish"
                );
        if (!sameLanguage(sourceDialogueLanguage, voiceLanguage)) {
            Map<String, Object> previewScene = new LinkedHashMap<>();
            previewScene.put("id", "founder-voice-preview");
            previewScene.put("sceneNumber", 1);
            previewScene.put("durationSeconds", 10);
            previewScene.put("dialogueScript", captionText);
            String sourcePreviewText = captionText;
            List<Map<String, Object>> localizedPreview = localizeDialogueScenes(
                    script,
                    List.of(previewScene),
                    sourceDialogueLanguage,
                    voiceLanguage,
                    voiceLanguageCode,
                    null
            );
            captionText = dialogueTextForScene(localizedPreview.get(0));
            spokenText = captionText;
            if (captionText.length() > 240) {
                captionText = captionText.substring(0, 240).trim();
                spokenText = captionText;
            }
            profile.put("sourceVoicePreviewText", sourcePreviewText);
            profile.put("voicePreviewTranslationStatus", "COMPLETED");
            log.info(
                    "Founder voice preview localized requestId={} sourceLanguage={} targetLanguage={} "
                            + "sourceChars={} translatedChars={}",
                    voiceRequestId,
                    sourceDialogueLanguage,
                    voiceLanguage,
                    sourcePreviewText.length(),
                    captionText.length()
            );
        }

        localModels.put("voiceModel", voiceModel);
        profile.put("localModels", localModels);
        profile.put("referenceTranscript", referenceTranscript);
        profile.put("voicePreviewText", captionText);
        profile.put("pronunciationGuide", pronunciationGuide);
        profile.put("elevenLabsVoiceId", elevenLabsVoiceId);
        profile.put("sarvamVoiceId", sarvamVoiceId);
        profile.put("voiceLanguage", voiceLanguage);
        profile.put("voiceLanguageCode", voiceLanguageCode);
        profile.put("referenceLanguage", referenceLanguage);
        profile.put("minimaxLanguageBoost", minimaxLanguageBoost);
        profile.put("voiceApprovalStatus", "GENERATING");

        Map<String, Object> voiceOptions = new LinkedHashMap<>();
        voiceOptions.put("provider", "dalai_llama");
        voiceOptions.put("voiceModel", voiceModel);
        voiceOptions.put("voiceProfileId", voiceProfileId);
        voiceOptions.put("localModels", localModels);
        voiceOptions.put("founderAvatarProfile", profile);
        voiceOptions.put("founderKit", profile);
        voiceOptions.put("sourceUrl", sourceUrl);
        voiceOptions.put("spokenText", spokenText);
        voiceOptions.put("captionText", captionText);
        voiceOptions.put("pronunciationGuide", pronunciationGuide);
        voiceOptions.put("promptText", referenceTranscript);
        voiceOptions.put("referenceTranscript", referenceTranscript);
        voiceOptions.put("elevenLabsVoiceId", elevenLabsVoiceId);
        voiceOptions.put("sarvamVoiceId", sarvamVoiceId);
        voiceOptions.put("minimaxVoiceId", "fal_minimax_voice_clone".equals(voiceModel) ? requestedProviderVoiceId : "");
        voiceOptions.put("providerVoiceId", requestedProviderVoiceId);
        voiceOptions.put("language", voiceLanguage);
        voiceOptions.put("languageCode", voiceLanguageCode);
        voiceOptions.put("referenceLanguage", referenceLanguage);
        voiceOptions.put("languageBoost", minimaxLanguageBoost);
        voiceOptions.put("preview", true);
        voiceOptions.put("requestId", voiceRequestId);

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                script.getTenantId(), script.getUserId(), script.getProjectId(), null, null
        );
        log.info(
                "Founder voice preview started requestId={} scriptId={} model={} language={} preview={} hasExistingProviderVoice={} hasSourceObject={}",
                voiceRequestId,
                script.getId(),
                voiceModel,
                firstText(profile.get("language"), "Hinglish"),
                true,
                !requestedProviderVoiceId.isBlank(),
                !firstText(firstMap(profile.get("sourceAsset")).get("objectKey"), profile.get("sourceObjectKey")).isBlank()
        );
        creatorAiService.assertWalletBalanceForModelRun("FOUNDER_VOICE_PREVIEW", usageContext);
        long voiceGenerationStartedNanos = System.nanoTime();
        GoogleChirpVoiceGenerationService.GeneratedVoice voice;
        try {
            voice = voiceGenerationService.generateVoice(spokenText, voiceOptions);
        } catch (RuntimeException ex) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - voiceGenerationStartedNanos);
            log.error(
                    "Founder voice preview failed requestId={} scriptId={} model={} elapsedMs={} errorType={} errorMessage={}",
                    voiceRequestId,
                    script.getId(),
                    voiceModel,
                    elapsedMs,
                    ex.getClass().getSimpleName(),
                    firstText(ex.getMessage(), "unknown")
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Voice clone failed before the approval preview. Reference: " + voiceRequestId,
                    ex
            );
        }
        Map<String, Object> voiceMetadata = new LinkedHashMap<>(voice.metadata());
        String providerVoiceId = firstText(
                voiceMetadata.get("providerVoiceId"),
                voiceMetadata.get("customVoiceId"),
                voice.providerResponse().get("custom_voice_id"),
                voice.providerResponse().get("customVoiceId"),
                voice.providerResponse().get("voiceId")
        );
        if (!providerVoiceId.isBlank()) {
            voiceMetadata.put("providerVoiceId", providerVoiceId);
            voiceMetadata.put("customVoiceId", providerVoiceId);
            profile.put("providerVoiceId", providerVoiceId);
            if (clientRvcVoice) {
                profile.put("voiceProfileId", voiceProfileId);
            } else if ("fal_elevenlabs_v3".equals(voiceModel)
                    || "elevenlabs_v3_voice_clone".equals(voiceModel)
                    || "elevenlabs_professional".equals(voiceModel)) {
                profile.put("elevenLabsVoiceId", providerVoiceId);
            } else if ("sarvam_voice_clone".equals(voiceModel)) {
                profile.put("sarvamVoiceId", providerVoiceId);
            } else {
                profile.put("minimaxVoiceId", providerVoiceId);
            }
        }
        Map<String, Object> referencePreparation = firstMap(voice.providerResponse().get("referencePreparation"));
        Map<String, Object> voiceEnhancement = firstMap(voice.providerResponse().get("voiceEnhancement"));
        String voiceCloneStatus = firstText(
                voice.providerResponse().get("voiceCloneStatus"),
                providerVoiceId.isBlank() ? "" : "completed"
        );
        if (!referencePreparation.isEmpty()) {
            profile.put("voiceReferencePreparation", referencePreparation);
            voiceMetadata.put("referencePreparation", referencePreparation);
        }
        if (!voiceCloneStatus.isBlank()) {
            profile.put("voiceCloneStatus", voiceCloneStatus);
            voiceMetadata.put("voiceCloneStatus", voiceCloneStatus);
        }
        if (!voiceEnhancement.isEmpty()) {
            profile.put("voiceEnhancement", voiceEnhancement);
            profile.put("voiceEnhancementStatus", firstText(
                    voice.providerResponse().get("voiceEnhancementStatus"),
                    voiceEnhancement.get("status")
            ));
            profile.put("voiceEnhancementApplied", booleanValue(
                    voice.providerResponse().get("voiceEnhancementApplied"),
                    booleanValue(voiceEnhancement.get("applied"), false)
            ));
            profile.put("voiceEnhancementProfile", firstText(
                    voice.providerResponse().get("voiceEnhancementProfile"),
                    voiceEnhancement.get("profile"),
                    "studio_voice_v1"
            ));
            voiceMetadata.put("voiceEnhancement", voiceEnhancement);
        }
        voiceMetadata.put("preview", true);
        voiceMetadata.put("captionText", captionText);
        voiceMetadata.put("spokenText", spokenText);
        Map<String, Object> previewAsset = storeAudioAsset(
                script,
                UUID.randomUUID(),
                voice.bytes(),
                voice.contentType(),
                "founder_voice_preview",
                voiceMetadata,
                voice.providerRequest(),
                voice.providerResponse()
        );

        profile.put("voicePreviewAsset", previewAsset);
        profile.put("voiceApprovalStatus", "PREVIEW_READY");
        profile.put("voicePreviewAttempts", intValue(profile.get("voicePreviewAttempts"), 0) + 1);
        profile.put("voicePreviewGeneratedAt", OffsetDateTime.now().toString());
        profile.put("lastVoiceRequestId", voiceRequestId);
        invalidateAvatarPreview(profile);
        profile.remove("voiceApprovedAt");
        profile.remove("voiceApprovedBy");
        attachFounderAvatarToScript(script, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> costMetadata = firstMap(voiceMetadata.get("costMetadata"));
        creatorAiService.publishProviderUsageDebit(
                "FOUNDER_VOICE_PREVIEW",
                firstText(voiceMetadata.get("provider"), "dalai_llama"),
                firstText(voiceMetadata.get("model"), voiceModel),
                costMetadata,
                usageContext,
                "Generated founder voice approval preview"
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "PREVIEW_READY");
        response.put("requestId", voiceRequestId);
        response.put("voiceModel", voiceModel);
        response.put("voiceProfileId", voiceProfileId);
        response.put("providerVoiceId", providerVoiceId);
        response.put("minimaxVoiceId", "fal_minimax_voice_clone".equals(voiceModel) ? providerVoiceId : "");
        response.put("elevenLabsVoiceId", elevenLabsVoiceModel ? providerVoiceId : "");
        response.put("sarvamVoiceId", "sarvam_voice_clone".equals(voiceModel) ? providerVoiceId : "");
        response.put("voiceCloneStatus", voiceCloneStatus);
        response.put("referencePreparation", referencePreparation);
        response.put("previewAsset", previewAsset);
        response.put("founderAvatarProfile", profile);
        log.info(
                "Founder voice preview completed requestId={} scriptId={} model={} provider={} elapsedMs={} audioBytes={} cloneCreated={}",
                voiceRequestId,
                script.getId(),
                voiceModel,
                firstText(voiceMetadata.get("provider"), "dalai_llama"),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - voiceGenerationStartedNanos),
                voice.bytes() == null ? 0 : voice.bytes().length,
                !providerVoiceId.isBlank()
        );
        return response;
    }

    @Transactional
    public Map<String, Object> approveFounderVoicePreview(
            UUID scriptId,
            Map<String, Object> requestBody,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> request = copyMap(requestBody);
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> profile = founderAvatarProfile(request, scriptPayload, firstMap(scriptPayload.get("creatorContext")));
        String decision = defaultString(firstText(request.get("decision")), "APPROVE").toUpperCase(Locale.ROOT);
        if (!List.of("APPROVE", "REJECT", "RESET").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Voice decision must be APPROVE, REJECT, or RESET.");
        }

        String voiceModel = normalizeLocalVoiceModel(firstText(
                request.get("voiceModel"),
                firstMap(profile.get("localModels")).get("voiceModel")
        ));
        Map<String, Object> previewAsset = firstMap(profile.get("voicePreviewAsset"));
        Map<String, Object> finalAudioAsset = firstMap(profile.get("exactFounderAudioAsset"), profile.get("finalFounderAudioAsset"));
        if ("APPROVE".equals(decision)
                && previewAsset.isEmpty()
                && finalAudioAsset.isEmpty()
                && !"synthesia_managed".equals(voiceModel)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Generate a voice preview or upload final founder audio before approval.");
        }
        if ("APPROVE".equals(decision) && "synthesia_managed".equals(voiceModel)
                && firstText(profile.get("synthesiaVoiceId"), profile.get("voiceId")).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add the Synthesia managed voice ID before approval.");
        }

        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(profile.get("localModels")));
        localModels.put("voiceModel", voiceModel);
        profile.put("localModels", localModels);
        profile.put("referenceTranscript", firstText(request.get("referenceTranscript"), profile.get("referenceTranscript")));
        profile.put("voicePreviewText", firstText(request.get("previewText"), profile.get("voicePreviewText")));
        profile.put("spokenText", firstText(request.get("spokenText"), profile.get("spokenText")));
        profile.put("pronunciationGuide", firstText(request.get("pronunciationGuide"), profile.get("pronunciationGuide")));
        profile.put("elevenLabsVoiceId", firstText(request.get("elevenLabsVoiceId"), profile.get("elevenLabsVoiceId")));
        profile.put("sarvamVoiceId", firstText(request.get("sarvamVoiceId"), profile.get("sarvamVoiceId")));
        if ("APPROVE".equals(decision)) {
            profile.put("voiceApprovalStatus", "APPROVED");
            profile.put("voiceApprovedAt", OffsetDateTime.now().toString());
            profile.put("voiceApprovedBy", defaultString(userId, "anonymous"));
        } else {
            profile.put("voiceApprovalStatus", "REJECT".equals(decision) ? "REJECTED" : "NOT_REQUESTED");
            profile.remove("voiceApprovedAt");
            profile.remove("voiceApprovedBy");
            invalidateAvatarPreview(profile);
        }
        attachFounderAvatarToScript(script, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", profile.get("voiceApprovalStatus"));
        response.put("founderAvatarProfile", profile);
        return response;
    }

    @Transactional
    public Map<String, Object> uploadFounderFinalAudio(
            UUID scriptId,
            MultipartFile file,
            String captionText,
            boolean consentConfirmed,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        if (!consentConfirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Founder consent is required before uploading final founder audio.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the founder's final audio recording.");
        }
        String contentType = firstText(file.getContentType(), "application/octet-stream");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("audio/") && !isVideoContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Final founder audio must be an audio file or a video containing the final recording.");
        }

        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> profile = founderAvatarProfile(Map.of(), scriptPayload, firstMap(scriptPayload.get("creatorContext")));
        if (!booleanValue(profile.get("consentConfirmed"), false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the consented founder reference video before final audio.");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "founder_upload");
        metadata.put("model", "uploaded_founder_audio");
        metadata.put("captionText", firstText(captionText));
        metadata.put("originalFilename", firstText(file.getOriginalFilename(), "founder-final-audio"));
        Map<String, Object> audioAsset;
        try {
            audioAsset = storeAudioAsset(
                    script,
                    UUID.randomUUID(),
                    file.getBytes(),
                    contentType,
                    "founder_final_voice",
                    metadata,
                    Map.of("source", "founder_upload"),
                    Map.of("status", "UPLOADED")
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded founder audio.", ex);
        }

        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(profile.get("localModels")));
        localModels.put("voiceModel", "uploaded_founder_audio");
        profile.put("localModels", localModels);
        profile.put("exactFounderAudioAsset", audioAsset);
        profile.put("finalFounderAudioAsset", audioAsset);
        profile.put("finalFounderAudioUrl", firstText(audioAsset.get("assetUrl")));
        profile.put("spokenText", firstText(captionText, profile.get("spokenText")));
        profile.put("voiceApprovalStatus", "APPROVED");
        profile.put("voiceApprovedAt", OffsetDateTime.now().toString());
        profile.put("voiceApprovedBy", defaultString(userId, "anonymous"));
        profile.put("finalFounderAudioUploadedAt", OffsetDateTime.now().toString());
        invalidateAvatarPreview(profile);
        attachFounderAvatarToScript(script, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "APPROVED");
        response.put("audioAsset", audioAsset);
        response.put("founderAvatarProfile", profile);
        return response;
    }

    private void attachReferenceImageToScript(CreatorScript script, Map<String, Object> referenceAsset, String details, boolean enhanceScreenplay) {
        if (script == null || referenceAsset == null || referenceAsset.isEmpty()) {
            return;
        }
        Map<String, Object> payload = copyMap(script.getScriptPayload());
        Map<String, Object> creatorContext = copyMap(payload.get("creatorContext"));
        Map<String, Object> metadata = copyMap(creatorContext.get("metadata"));
        String url = firstText(referenceAsset.get("signedUrl"), referenceAsset.get("publicUrl"), referenceAsset.get("assetUrl"));
        Map<String, Object> assetSummary = new LinkedHashMap<>(referenceAsset);
        assetSummary.remove("metadata");

        appendUniqueValue(payload, "referenceImageUrls", url);
        appendUniqueValue(payload, "productImageUrls", url);
        appendUniqueValue(creatorContext, "referenceImageUrls", url);
        appendUniqueValue(creatorContext, "productImageUrls", url);
        appendUniqueValue(metadata, "referenceImageUrls", url);
        appendUniqueAsset(payload, "referenceImageAssets", assetSummary);
        appendUniqueAsset(payload, "productImageAssets", assetSummary);
        appendUniqueAsset(creatorContext, "referenceImageAssets", assetSummary);
        appendUniqueAsset(creatorContext, "productImageAssets", assetSummary);

        String cleanDetails = firstText(details);
        if (!cleanDetails.isBlank()) {
            payload.put("referenceImageDetails", cleanDetails);
            creatorContext.put("referenceImageDetails", cleanDetails);
            metadata.put("referenceImageDetails", cleanDetails);
            if (enhanceScreenplay) {
                payload.put("screenplayEnhancementReferenceDetails", cleanDetails);
                creatorContext.put("screenplayEnhancementReferenceDetails", cleanDetails);
            }
        }
        payload.put("screenplayReferenceEnhancementEnabled", enhanceScreenplay);
        creatorContext.put("screenplayReferenceEnhancementEnabled", enhanceScreenplay);
        creatorContext.put("metadata", metadata);
        payload.put("creatorContext", creatorContext);
        script.setScriptPayload(payload);
        scriptRepository.saveAndFlush(script);
    }

    private void attachFounderAvatarToScript(CreatorScript script, Map<String, Object> sourceAsset, Map<String, Object> profile) {
        if (script == null || profile == null || profile.isEmpty()) {
            return;
        }
        Map<String, Object> payload = copyMap(script.getScriptPayload());
        Map<String, Object> creatorContext = copyMap(payload.get("creatorContext"));
        Map<String, Object> metadata = copyMap(creatorContext.get("metadata"));
        Map<String, Object> profileCopy = new LinkedHashMap<>(profile);
        Map<String, Object> assetCopy = new LinkedHashMap<>(sourceAsset == null ? Map.of() : sourceAsset);
        assetCopy.remove("founderAvatarProfile");
        profileCopy.put("sourceAsset", assetCopy);

        payload.put("founderAvatarProfile", profileCopy);
        payload.put("founderKit", profileCopy);
        payload.put("founderLedHybridEnabled", true);
        payload.put("avatarProviderMode", firstText(profileCopy.get("avatarProviderMode"), profileCopy.get("providerMode"), "synthesia"));
        payload.put("avatarProvider", payload.get("avatarProviderMode"));
        payload.put("avatarId", firstText(profileCopy.get("avatarId")));
        payload.put("voiceId", firstText(profileCopy.get("voiceId")));
        payload.put("portraitEmbeddingId", firstText(profileCopy.get("portraitEmbeddingId")));
        payload.put("facialFeatureEmbeddingId", firstText(profileCopy.get("facialFeatureEmbeddingId")));
        payload.put("voiceEmbeddingId", firstText(profileCopy.get("voiceEmbeddingId")));
        payload.put("dialogueLanguage", firstText(payload.get("dialogueLanguage"), "Hinglish"));
        payload.put("voiceProvider", avatarVoiceProvider(profileCopy));

        creatorContext.put("founderAvatarProfile", profileCopy);
        creatorContext.put("founderKit", profileCopy);
        creatorContext.put("founderLedHybridEnabled", true);
        creatorContext.put("avatarProviderMode", payload.get("avatarProviderMode"));
        creatorContext.put("avatarId", payload.get("avatarId"));
        creatorContext.put("voiceId", payload.get("voiceId"));
        metadata.put("founderAvatarProfile", profileCopy);
        metadata.put("founderLedHybridEnabled", true);
        metadata.put("avatarProviderMode", payload.get("avatarProviderMode"));
        creatorContext.put("metadata", metadata);
        payload.put("creatorContext", creatorContext);

        script.setDialogueLanguage(firstText(script.getDialogueLanguage(), "Hinglish"));
        script.setScriptPayload(payload);
        scriptRepository.saveAndFlush(script);
    }

    private void invalidateAvatarPreview(Map<String, Object> profile) {
        if (profile == null) {
            return;
        }
        profile.put("avatarPreviewStatus", "NOT_REQUESTED");
        List.of(
                "avatarPreviewAsset",
                "avatarPreviewUrl",
                "avatarPreviewGeneratedAt",
                "avatarPreviewDialogue",
                "avatarPreviewLanguage",
                "avatarPreviewLanguageCode",
                "avatarPreviewFingerprint",
                "avatarPreviewRequestId",
                "avatarPreviewApprovedAt",
                "avatarPreviewApprovedBy"
        ).forEach(profile::remove);
    }

    private void appendUniqueValue(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        List<Object> values = new ArrayList<>(firstList(target.get(key)));
        if (values.stream().noneMatch(item -> value.equals(String.valueOf(item)))) {
            values.add(value);
        }
        target.put(key, values);
    }

    private void appendUniqueAsset(Map<String, Object> target, String key, Map<String, Object> asset) {
        if (target == null || key == null || key.isBlank() || asset == null || asset.isEmpty()) {
            return;
        }
        String assetId = firstText(asset.get("assetId"), asset.get("id"));
        List<Map<String, Object>> assets = mapListValue(target.get(key));
        boolean exists = !assetId.isBlank() && assets.stream().anyMatch(item -> assetId.equals(firstText(item.get("assetId"), item.get("id"))));
        if (!exists) {
            assets.add(new LinkedHashMap<>(asset));
        }
        target.put(key, assets);
    }

    @Transactional
    public CreatorGenerationJob startVideoGenerationJob(
            UUID scriptId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorScript script = loadScript(scriptId, safeTenantId, safeUserId);
        Map<String, Object> inputPayload = copyMap(request);
        trimClientReviewHistory(inputPayload);
        boolean prepareOnly = booleanValue(inputPayload.get("prepareOnly"), false)
                || "scene_by_scene".equalsIgnoreCase(firstText(inputPayload.get("generationWorkflow")));
        String provider = normalizeVideoProvider(firstText(inputPayload.get("provider"), inputPayload.get("targetProvider"), inputPayload.get("modelProvider")));
        String model = modelForProvider(provider, firstText(inputPayload.get("model"), inputPayload.get("videoModel"), inputPayload.get("providerModel")));
        int maxClipSeconds = modelCapabilityMaxClipSeconds(provider, model, inputPayload.get("maxClipSeconds"));

        CreatorGenerationJob latestVideoJob = generationJobRepository
                .findLatestScreenplayVideoRunJobForScript(safeTenantId, safeUserId, script.getId().toString())
                .orElse(null);
        Map<String, Object> previousRun = latestVideoJob == null
                ? new LinkedHashMap<>()
                : firstNonEmptyMap(
                        copyMap(latestVideoJob.getOutputPayload()).get("videoRun"),
                        copyMap(latestVideoJob.getOutputPayload()).get("screenplayVideoRun"),
                        copyMap(latestVideoJob.getOutputPayload())
                );
        UUID previousRunId = uuidValue(previousRun.get("runId"));
        boolean forceNewRun = booleanValue(inputPayload.get("forceNewRun"), false);
        boolean billingConsent = booleanValue(inputPayload.get("billingConsent"), false);

        if (!forceNewRun && previousRunId != null && isResumableVideoRun(previousRun, latestVideoJob)) {
                inputPayload.put("scriptId", script.getId().toString());
                inputPayload.put("runId", previousRunId.toString());
                inputPayload.put("provider", provider);
                inputPayload.put("model", model);
                inputPayload.put("maxClipSeconds", maxClipSeconds);
                inputPayload.put("screenplayVideoRunResumedAt", OffsetDateTime.now().toString());
                CreatorGenerationJob resumedJob = generationJobService.startGenerationJob(
                        JOB_SCREENPLAY_VIDEO_GENERATE,
                        safeTenantId,
                        safeUserId,
                        script.getProjectId(),
                        inputPayload
                );
                previousRun.put("jobId", resumedJob.getId().toString());
                previousRun.put("provider", provider);
                previousRun.put("model", model);
                previousRun.put("maxClipSeconds", maxClipSeconds);
                String resumedStatus = prepareOnly ? "SCENES_READY_FOR_GENERATION" : "SCENE_QUEUE_QUEUED";
                String resumedMessage = prepareOnly
                        ? "Scene workspace ready. Generate and review one scene at a time."
                        : "Resuming only the remaining scene clips. Existing generated clips will be kept.";
                previousRun.put("status", resumedStatus);
                previousRun.put("message", resumedMessage);
                previousRun.put("generationWorkflow", prepareOnly ? "scene_by_scene" : "complete_pipeline");
                previousRun.put("prepareOnly", prepareOnly);
                previousRun.put("resumedAt", OffsetDateTime.now().toString());
                previousRun.put("resumedFromJobId", latestVideoJob.getId().toString());
                previousRun.put("resumeCount", intValue(previousRun.get("resumeCount"), 0) + 1);
                synchronizeAvatarDialogueSources(script, previousRun);
                log.info("Resuming screenplay video queue scriptId={} runId={} jobId={} existingClips={} totalScenes={} provider={} model={}",
                        script.getId(),
                        previousRunId,
                        resumedJob.getId(),
                        mapListValue(previousRun.get("scenes")).stream().filter(this::hasClipAsset).count(),
                        mapListValue(previousRun.get("scenes")).size(),
                        provider,
                        model);
                return generationJobService.updateGenerationJobProgress(
                        resumedJob.getId(),
                        prepareOnly ? 100 : 6,
                        resumedMessage,
                        outputPayload(previousRun, resumedMessage)
                );
        }

        boolean priorRunHasGeneratedClips = mapListValue(previousRun.get("scenes")).stream().anyMatch(this::hasClipAsset);
        if (priorRunHasGeneratedClips && !billingConsent && !prepareOnly) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This starts a new paid full-video run. Confirm the Rs. "
                            + positiveMoney(aiShortStarterPriceInr).setScale(0, RoundingMode.HALF_UP)
                            + " package charge and retry with billingConsent=true."
            );
        }
        UUID runId = UUID.randomUUID();

        inputPayload.put("scriptId", script.getId().toString());
        inputPayload.put("runId", runId.toString());
        inputPayload.put("provider", provider);
        inputPayload.put("model", model);
        inputPayload.put("maxClipSeconds", maxClipSeconds);
        inputPayload.put("billingConsent", billingConsent);
        inputPayload.put("billingMode", prepareOnly
                ? "SCENE_WORKSPACE"
                : priorRunHasGeneratedClips ? "PAID_FULL_RERUN" : "INITIAL_VIDEO_PACKAGE");
        inputPayload.put("screenplayVideoRunCreatedAt", OffsetDateTime.now().toString());

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_GENERATE,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                inputPayload
        );

        Map<String, Object> run = buildInitialRun(script, inputPayload, runId, job.getId(), provider, model, maxClipSeconds);
        run.put("billingMode", inputPayload.get("billingMode"));
        synchronizeAvatarDialogueSources(script, run);
        run.put("billingConsent", billingConsent);
        if (priorRunHasGeneratedClips) {
            run.put("rerunsFromRunId", previousRunId == null ? "" : previousRunId.toString());
        }
        refreshBillingSummary(run);
        run.put("generationWorkflow", prepareOnly ? "scene_by_scene" : "complete_pipeline");
        run.put("prepareOnly", prepareOnly);
        run.put("status", prepareOnly ? "SCENES_READY_FOR_GENERATION" : "SCENE_QUEUE_QUEUED");
        String queueMessage = prepareOnly
                ? "Scene workspace ready. Generate and review one scene at a time."
                : priorRunHasGeneratedClips
                    ? "A confirmed paid full-video rerun is queued. Existing clips remain in the previous run."
                    : "Shot queue is ready. Generating scenes sequentially.";
        run.put("message", queueMessage);
        return generationJobService.updateGenerationJobProgress(
                job.getId(),
                prepareOnly ? 100 : 6,
                queueMessage,
                outputPayload(run, queueMessage)
        );
    }

    @Transactional
    public CreatorGenerationJob runVideoGenerationJob(
            UUID jobId,
            UUID scriptId,
            UUID runId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        if (jobId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generation job id is required.");
        }
        if (runId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video run id is required.");
        }
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        UUID runScriptId = uuidValue(run.get("scriptId"));
        if (scriptId != null && runScriptId != null && !scriptId.equals(runScriptId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay video run was not found for this script.");
        }
        CreatorScript script = loadScript(runScriptId == null ? scriptId : runScriptId, safeTenantId, safeUserId);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        scenes = enrichScenesWithStoryboardReferences(script, scenes, request);
        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        if (scenes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenplay scenes are missing for video generation.");
        }

        int totalScenes = scenes.size();
        long queueStartedNanos = System.nanoTime();
        log.info("Screenplay video queue worker started jobId={} scriptId={} runId={} scenes={} provider={} model={}",
                jobId, script.getId(), runId, totalScenes, firstText(run.get("provider")), firstText(run.get("model")));
        generationJobService.updateGenerationJobProgress(
                jobId,
                10,
                "Generating " + totalScenes + " scene clips",
                outputPayload(run, "Generating " + totalScenes + " scene clips")
        );

        for (int index = 0; index < totalScenes; index++) {
            Map<String, Object> scene = index < scenes.size() ? scenes.get(index) : Map.of();
            Map<String, Object> sceneOverride = requestSceneOverride(request, scene, index + 1);
            if (!sceneOverride.isEmpty()) {
                Map<String, Object> updatedScene = copyMap(scene);
                updatedScene.putAll(sceneOverride);
                scene = updatedScene;
                scenes.set(index, updatedScene);
            }
            String sceneId = firstText(scene.get("id"), scene.get("sceneId"), scene.get("scene_id"), "scene-" + (index + 1));
            if (hasClipAsset(scene)) {
                int skippedProgress = sceneProgress(index + 1, totalScenes);
                generationJobService.updateGenerationJobProgress(
                        jobId,
                        skippedProgress,
                        "Scene " + (index + 1) + " already has a generated clip",
                        outputPayload(run, "Scene " + (index + 1) + " already has a generated clip")
                );
                continue;
            }

            Map<String, Object> sceneRequest = copyMap(request);
            sceneRequest.putAll(sceneOverride);
            sceneRequest.put("batchParentJobId", jobId.toString());
            sceneRequest.put("batchSceneIndex", index);
            sceneRequest.put("batchSceneCount", totalScenes);

            Map<String, Object> generatingScene = copyMap(scene);
            generatingScene.put("status", "GENERATING");
            generatingScene.put("queuePosition", index + 1);
            generatingScene.put("updatedAt", OffsetDateTime.now().toString());
            scenes.set(index, generatingScene);
            run.put("scenes", scenes);
            run.put("sceneClips", scenes);
            run.put("status", "VIDEO_GENERATING");
            run.put("message", "Generating scene " + (index + 1) + " of " + totalScenes);
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    sceneProgress(index, totalScenes),
                    "Generating scene " + (index + 1) + " of " + totalScenes,
                    outputPayload(run, "Generating scene " + (index + 1) + " of " + totalScenes)
            );
            long sceneStartedNanos = System.nanoTime();
            CreatorGenerationJob sceneJob = startRegenerateSceneJob(runId, sceneId, sceneRequest, safeTenantId, safeUserId);
            try {
                runRegenerateSceneJob(sceneJob.getId(), runId, sceneId, sceneRequest, safeTenantId, safeUserId);
                log.info("Screenplay video queue scene completed queueJobId={} sceneJobId={} runId={} sceneId={} sceneNumber={} elapsedMs={}",
                        jobId,
                        sceneJob.getId(),
                        runId,
                        sceneId,
                        index + 1,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sceneStartedNanos));
            } catch (RuntimeException ex) {
                log.warn("Screenplay video queue paused after scene failure queueJobId={} sceneJobId={} runId={} sceneId={} sceneNumber={} elapsedMs={} errorType={} errorMessage={}",
                        jobId,
                        sceneJob.getId(),
                        runId,
                        sceneId,
                        index + 1,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sceneStartedNanos),
                        ex.getClass().getSimpleName(),
                        ex.getMessage());
                throw ex;
            }

            RunRecord latestRecord = loadRun(runId, safeTenantId, safeUserId);
            run = copyMap(latestRecord.run());
            scenes = mapListValue(run.get("scenes"));
            if ("WAITING_FOR_MANUAL_AVATAR_APPROVAL".equals(firstText(run.get("status")))) {
                generationJobService.updateGenerationJobProgress(
                        jobId,
                        sceneProgress(index + 1, totalScenes),
                        stringValue(run.get("message"), "Waiting for manual avatar fallback approval."),
                        outputPayload(run, stringValue(run.get("message"), "Waiting for manual avatar fallback approval."))
                );
                break;
            }
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    sceneProgress(index + 1, totalScenes),
                    "Generated scene " + (index + 1) + " of " + totalScenes,
                    outputPayload(run, "Generated scene " + (index + 1) + " of " + totalScenes)
            );
        }

        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        boolean allSceneClipsReady = allScenesHaveClips(scenes);
        boolean waitingForAvatarApproval = "WAITING_FOR_MANUAL_AVATAR_APPROVAL".equals(firstText(run.get("status")));
        if (!waitingForAvatarApproval) {
            run.put("status", allSceneClipsReady ? "SCENE_CLIPS_READY" : "PARTIAL_SCENE_CLIPS_READY");
        }
        run.put("updatedAt", OffsetDateTime.now().toString());
        if (!waitingForAvatarApproval) {
            run.put("message", allSceneClipsReady
                    ? "All scene clips generated. Combining the final preview automatically."
                    : "Some scene clips are still missing. Review failed scenes before combining.");
        }

        if (allSceneClipsReady && !waitingForAvatarApproval) {
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    90,
                    "All scene clips are ready. Combining the final preview.",
                    outputPayload(run, "All scene clips are ready. Combining the final preview.")
            );
            try {
                Map<String, Object> automaticRenderRequest = automaticFinalRenderRequest(run, request, "scene_queue");
                CreatorGenerationJob finalJob = startFinalRenderJob(runId, automaticRenderRequest, safeTenantId, safeUserId);
                run.put("automaticFinalRenderJobId", finalJob.getId().toString());
                run.put("automaticFinalRenderStatus", "RUNNING");
                generationJobService.updateGenerationJobProgress(
                        jobId,
                        94,
                        "Combining generated clips into the final preview.",
                        outputPayload(run, "Combining generated clips into the final preview.")
                );
                CreatorGenerationJob completedFinalJob = runFinalRenderJob(
                        finalJob.getId(),
                        runId,
                        automaticRenderRequest,
                        safeTenantId,
                        safeUserId
                );
                RunRecord mergedRecord = loadRun(runId, safeTenantId, safeUserId);
                run = copyMap(mergedRecord.run());
                scenes = mapListValue(run.get("scenes"));
                run.put("automaticFinalRenderJobId", finalJob.getId().toString());
                run.put("automaticFinalRenderStatus", completedFinalJob.getStatus());
                run.put("message", "All scene clips generated and final preview rendered.");
            } catch (RuntimeException ex) {
                run.put("automaticFinalRenderStatus", "FAILED");
                run.put("automaticFinalRenderError", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                run.put("message", "All scene clips generated. The final preview can be retried from this screen.");
                log.warn("Automatic final merge failed after scene queue scriptId={} runId={} errorType={} errorMessage={}",
                        script.getId(), runId, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }

        log.info("Screenplay video queue worker completed jobId={} scriptId={} runId={} totalElapsedMs={} finalStatus={}",
                jobId,
                script.getId(),
                runId,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queueStartedNanos),
                run.get("status"));
        return generationJobService.completeGenerationJob(jobId, outputPayload(run, stringValue(run.get("message"), "Scene video clips generated.")));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getVideoRun(UUID scriptId, UUID runId, String tenantId, String userId) {
        RunRecord record = loadRun(runId, tenantId, userId);
        UUID runScriptId = uuidValue(record.run().get("scriptId"));
        if (scriptId != null && runScriptId != null && !scriptId.equals(runScriptId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay video run was not found for this script.");
        }
        return hydrateVideoRunForResponse(record.run(), tenantId, userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLatestVideoRun(UUID scriptId, String tenantId, String userId) {
        if (scriptId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Script id is required.");
        }
        CreatorGenerationJob job = generationJobRepository
                .findLatestScreenplayVideoRunJobForScript(
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous"),
                        scriptId.toString()
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No screenplay video run was found for this script."));
        Map<String, Object> output = copyMap(job.getOutputPayload());
        Map<String, Object> run = firstNonEmptyMap(output.get("videoRun"), output.get("screenplayVideoRun"), output);
        UUID runScriptId = uuidValue(run.get("scriptId"));
        if (runScriptId != null && !scriptId.equals(runScriptId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay video run was not found for this script.");
        }
        videoRunHydrator().mergeLatestAudioState(run, defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), firstText(run.get("runId")));
        return hydrateVideoRunForResponse(run, tenantId, userId);
    }

    @Transactional
    public Map<String, Object> chatScene(
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        String message = firstText(
                request == null ? null : request.get("message"),
                request == null ? null : request.get("editRequest"),
                request == null ? null : request.get("instruction")
        );
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scene edit message is required.");
        }

        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        scenes = enrichScenesWithStoryboardReferences(script, scenes, request);
        int sceneIndex = findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        Map<String, Object> ragContext = buildRagContext(script, run, scenes, sceneIndex, request);
        String renderedPrompt = buildSceneEditPrompt(run, scene, message, ragContext);

        Map<String, Object> inputPayload = copyMap(request);
        inputPayload.put("runId", runId.toString());
        inputPayload.put("scriptId", script.getId().toString());
        Object preservedTargetDuration = firstValue(
                inputPayload.get("targetDurationSeconds"),
                inputPayload.get("durationSeconds"),
                run.get("targetDurationSeconds"),
                run.get("durationSeconds")
        );
        if (preservedTargetDuration != null) {
            inputPayload.put("targetDurationSeconds", preservedTargetDuration);
        }
        inputPayload.put("sceneId", sceneId);
        inputPayload.put("message", message);
        inputPayload.put("ragContext", ragContext);
        inputPayload.put("provider", normalizeVideoProvider(firstText(request == null ? null : request.get("provider"), run.get("provider"))));
        inputPayload.put("model", modelForProvider(
                stringValue(inputPayload.get("provider"), "seedance"),
                firstText(request == null ? null : request.get("model"), run.get("model"))
        ));
        CreatorGenerationJob chatJob = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_SCENE_CHAT,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                inputPayload
        );

        SceneEditResult editResult = sceneChatEditor.generateEditedScene(script, scene, message, ragContext, renderedPrompt, chatJob.getId());
        Map<String, Object> editedScene = applySceneEdit(scene, editResult.scene(), run, request);
        editedScene.put("status", "NEEDS_REGENERATION");
        editedScene.put("ragContext", ragContext);
        editedScene.put("providerRequest", buildProviderRequest(run, editedScene, request));
        appendRevision(editedScene, message, scene, editResult, ragContext);
        scenes.set(sceneIndex, editedScene);
        shotPlanTagGateway.persistEditedTag(script.getId(), editedScene, sceneIndex);

        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        run.put("status", "NEEDS_REGENERATION");
        run.put("updatedAt", OffsetDateTime.now().toString());
        run.put("lastSceneEdit", Map.of(
                "sceneId", sceneId,
                "sceneNumber", editedScene.get("sceneNumber"),
                "message", message,
                "promptRunId", editResult.promptRunId() == null ? "" : editResult.promptRunId().toString(),
                "updatedAt", OffsetDateTime.now().toString()
        ));
        // Confirmed fix: previously this always completed the job, even when the AI edit itself
        // failed - the error sat in editResult.errorMessage(), buried in the response body, with
        // no signal in job status. Fail loudly instead so job status reflects real outcome.
        if (editResult.failed()) {
            generationJobService.failGenerationJob(chatJob.getId(), editResult.errorMessage(), outputPayload(run, editResult.errorMessage()));
        } else {
            generationJobService.completeGenerationJob(chatJob.getId(), outputPayload(run, "Scene edit saved with RAG context."));
        }
        return run;
    }

    @Transactional
    public Map<String, Object> generateSceneDialogueVoice(
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        return dialogueVoiceCloner().generateSceneDialogueVoice(runId, sceneId, request, tenantId, userId);
    }

    @Transactional
    public Map<String, Object> decideSceneDialogueVoice(
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        return dialogueVoiceCloner().decideSceneDialogueVoice(runId, sceneId, request, tenantId, userId);
    }

    @Transactional
    public Map<String, Object> combineSceneDialogueAudio(
            UUID runId,
            String tenantId,
            String userId
    ) {
        return dialogueVoiceCloner().combineSceneDialogueAudio(runId, tenantId, userId);
    }

    private DialogueVoiceCloner dialogueVoiceCloner() {
        return new DialogueVoiceCloner(
                this,
                generationJobRepository,
                generationJobService,
                avatarDialogueSyncGateway,
                avatarSceneDialogueService,
                creatorAiService,
                assetStorageService
        );
    }

    @Transactional
    public Map<String, Object> uploadSceneAvatarPortrait(
            UUID runId,
            String sceneId,
            MultipartFile file,
            String tenantId,
            String userId
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an avatar portrait image.");
        }
        String contentType = firstText(file.getContentType(), "application/octet-stream").toLowerCase(Locale.ROOT);
        String filename = firstText(file.getOriginalFilename(), "avatar-portrait.jpg");
        if (!contentType.startsWith("image/")
                && !filename.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|webp)$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar portrait must be a JPG, PNG, or WebP image.");
        }

        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        int sceneIndex = findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        String extension = contentType.contains("png") ? ".png" : contentType.contains("webp") ? ".webp" : ".jpg";
        String safeSceneId = defaultString(sceneId, "scene").replaceAll("[^A-Za-z0-9._-]", "_");
        String fingerprint = stableFingerprint(runId.toString(), sceneId, filename, String.valueOf(file.getSize()));
        String objectKey = "screenplay-videos/" + script.getId() + "/" + runId + "/avatar-inputs/"
                + safeSceneId + "-" + fingerprint.substring(0, 16) + extension;

        AssetStorageService.StoredObject stored;
        try (InputStream inputStream = file.getInputStream()) {
            stored = assetStorageService.uploadCreatorAssetFromStream(
                    objectKey,
                    inputStream,
                    contentType.startsWith("image/") ? contentType : "image/jpeg",
                    SIGNED_URL_TTL
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not upload the scene avatar image.", ex);
        }
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("bucket", stored.bucket());
        asset.put("objectKey", stored.objectKey());
        asset.put("contentType", stored.contentType());
        asset.put("sizeBytes", stored.sizeBytes());
        asset.put("originalFilename", filename);
        asset.put("assetUrl", stored.signedUrl());
        asset.put("signedUrl", stored.signedUrl());
        asset.put("publicUrl", stored.signedUrl());
        asset.put("assetKind", "scene_avatar_portrait");
        asset.put("uploadedAt", OffsetDateTime.now().toString());

        scene.put("avatarPortraitAsset", asset);
        scene.put("avatarPortraitUrl", stored.signedUrl());
        Map<String, Object> providerRequest = new LinkedHashMap<>(firstMap(scene.get("providerRequest")));
        providerRequest.put("avatarPortraitAsset", asset);
        scene.put("providerRequest", providerRequest);
        scene.put("updatedAt", OffsetDateTime.now().toString());
        scenes.set(sceneIndex, scene);
        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        run.put("updatedAt", OffsetDateTime.now().toString());

        Map<String, Object> jobInput = Map.of(
                "runId", runId.toString(),
                "scriptId", script.getId().toString(),
                "sceneId", sceneId,
                "objectKey", stored.objectKey()
        );
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_SCENE_PORTRAIT,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                jobInput
        );
        generationJobService.completeGenerationJob(job.getId(), outputPayload(run, "Scene avatar portrait uploaded."));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UPLOADED");
        response.put("asset", asset);
        response.put("scene", scene);
        response.put("videoRun", hydrateVideoRunForResponse(run, safeTenantId, safeUserId));
        return response;
    }

    @Transactional
    public Map<String, Object> uploadSceneProductionImage(
            UUID runId,
            String sceneId,
            MultipartFile file,
            String details,
            String tenantId,
            String userId
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a production image for this scene.");
        }
        String contentType = firstText(file.getContentType(), "application/octet-stream").toLowerCase(Locale.ROOT);
        String filename = firstText(file.getOriginalFilename(), "scene-production-image.jpg");
        if (!contentType.startsWith("image/")
                && !filename.toLowerCase(Locale.ROOT).matches(".*\\.(avif|gif|jpg|jpeg|png|webp)$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scene image must be a JPG, PNG, WebP, AVIF, or GIF image.");
        }

        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        int sceneIndex = findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        String extension = contentType.contains("png") ? ".png"
                : contentType.contains("webp") ? ".webp"
                : contentType.contains("avif") ? ".avif"
                : contentType.contains("gif") ? ".gif"
                : ".jpg";
        String safeSceneId = defaultString(sceneId, "scene").replaceAll("[^A-Za-z0-9._-]", "_");
        String fingerprint = stableFingerprint(runId.toString(), sceneId, filename, String.valueOf(file.getSize()));
        String objectKey = "screenplay-videos/" + script.getId() + "/" + runId + "/production-inputs/"
                + safeSceneId + "-" + fingerprint.substring(0, 16) + extension;

        AssetStorageService.StoredObject stored;
        try (InputStream inputStream = file.getInputStream()) {
            stored = assetStorageService.uploadCreatorAssetFromStream(
                    objectKey,
                    inputStream,
                    contentType.startsWith("image/") ? contentType : "image/jpeg",
                    SIGNED_URL_TTL
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not upload the scene production image.", ex);
        }

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("bucket", stored.bucket());
        asset.put("objectKey", stored.objectKey());
        asset.put("contentType", stored.contentType());
        asset.put("sizeBytes", stored.sizeBytes());
        asset.put("originalFilename", filename);
        asset.put("assetUrl", stored.signedUrl());
        asset.put("signedUrl", stored.signedUrl());
        asset.put("publicUrl", stored.signedUrl());
        asset.put("assetType", "PRODUCT_VISUAL_ANCHOR");
        asset.put("assetKind", "scene_production_image");
        asset.put("referenceRole", "generated_product_scene_frame");
        asset.put("details", firstText(details));
        asset.put("uploadedAt", OffsetDateTime.now().toString());

        scene.put("productionImage", asset);
        scene.put("productImageAsset", asset);
        scene.put("productImageAssets", List.of(asset));
        scene.put("generatedProductImageAssets", List.of(asset));
        scene.put("productionImageUrl", stored.signedUrl());
        scene.put("generatedProductImageUrl", stored.signedUrl());
        scene.put("imageAnchorUrl", stored.signedUrl());
        scene.put("productImageUrl", stored.signedUrl());
        Map<String, Object> providerRequest = new LinkedHashMap<>(firstMap(scene.get("providerRequest")));
        providerRequest.put("productImageAssets", List.of(asset));
        providerRequest.put("generatedProductImageAssets", List.of(asset));
        providerRequest.put("referenceImageAssets", List.of(asset));
        providerRequest.put("referenceImageUrl", stored.signedUrl());
        providerRequest.put("referenceImageMode", "use_when_available");
        scene.put("providerRequest", providerRequest);
        scene.put("updatedAt", OffsetDateTime.now().toString());
        scenes.set(sceneIndex, scene);
        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        run.put("updatedAt", OffsetDateTime.now().toString());

        Map<String, Object> jobInput = Map.of(
                "runId", runId.toString(),
                "scriptId", script.getId().toString(),
                "sceneId", sceneId,
                "objectKey", stored.objectKey()
        );
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_SCENE_IMAGE,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                jobInput
        );
        generationJobService.completeGenerationJob(job.getId(), outputPayload(run, "Scene production image uploaded."));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UPLOADED");
        response.put("asset", asset);
        response.put("scene", scene);
        response.put("videoRun", hydrateVideoRunForResponse(run, safeTenantId, safeUserId));
        return response;
    }

    // A separate, dedicated upload endpoint - same reasoning as uploadSceneProductionImage()
    // right above: this stays out of the JSON regenerate/generate-async request bodies so
    // neither endpoint's contract changes. Unlike that method, this one does NOT overwrite the
    // scene's persisted product/generated image fields - it's a one-off supplemental reference
    // the caller explicitly asked to either override or combine with whatever's already there.
    @Transactional
    public Map<String, Object> uploadSceneReferenceImage(
            UUID runId,
            String sceneId,
            MultipartFile file,
            String priority,
            String tenantId,
            String userId
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a reference image for this scene.");
        }
        String contentType = firstText(file.getContentType(), "application/octet-stream").toLowerCase(Locale.ROOT);
        String filename = firstText(file.getOriginalFilename(), "scene-reference-image.jpg");
        if (!contentType.startsWith("image/")
                && !filename.toLowerCase(Locale.ROOT).matches(".*\\.(avif|gif|jpg|jpeg|png|webp)$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reference image must be a JPG, PNG, WebP, AVIF, or GIF image.");
        }
        String safePriority = "override".equalsIgnoreCase(defaultString(priority, "")) ? "override" : "combine";

        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        int sceneIndex = findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        String extension = contentType.contains("png") ? ".png"
                : contentType.contains("webp") ? ".webp"
                : contentType.contains("avif") ? ".avif"
                : contentType.contains("gif") ? ".gif"
                : ".jpg";
        String safeSceneId = defaultString(sceneId, "scene").replaceAll("[^A-Za-z0-9._-]", "_");
        String fingerprint = stableFingerprint(runId.toString(), sceneId, filename, String.valueOf(file.getSize()));
        String objectKey = "screenplay-videos/" + script.getId() + "/" + runId + "/ad-hoc-references/"
                + safeSceneId + "-" + fingerprint.substring(0, 16) + extension;

        AssetStorageService.StoredObject stored;
        try (InputStream inputStream = file.getInputStream()) {
            stored = assetStorageService.uploadCreatorAssetFromStream(
                    objectKey,
                    inputStream,
                    contentType.startsWith("image/") ? contentType : "image/jpeg",
                    SIGNED_URL_TTL
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not upload the scene reference image.", ex);
        }

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("bucket", stored.bucket());
        asset.put("objectKey", stored.objectKey());
        asset.put("contentType", stored.contentType());
        asset.put("sizeBytes", stored.sizeBytes());
        asset.put("originalFilename", filename);
        asset.put("assetUrl", stored.signedUrl());
        asset.put("signedUrl", stored.signedUrl());
        asset.put("publicUrl", stored.signedUrl());
        asset.put("assetType", "PRODUCT_VISUAL_ANCHOR");
        asset.put("assetKind", "ad_hoc_scene_reference");
        asset.put("priority", safePriority);
        asset.put("uploadedAt", OffsetDateTime.now().toString());

        // Read directly by buildProviderRequestForScene() on the next generate/regenerate call -
        // see its adHocReferenceImageAsset/referenceImagePriority lines.
        scene.put("adHocReferenceImageAsset", asset);
        scene.put("referenceImagePriority", safePriority);
        scene.put("updatedAt", OffsetDateTime.now().toString());
        scenes.set(sceneIndex, scene);
        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        run.put("updatedAt", OffsetDateTime.now().toString());

        Map<String, Object> jobInput = Map.of(
                "runId", runId.toString(),
                "scriptId", script.getId().toString(),
                "sceneId", sceneId,
                "objectKey", stored.objectKey(),
                "priority", safePriority
        );
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_SCENE_IMAGE,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                jobInput
        );
        generationJobService.completeGenerationJob(job.getId(), outputPayload(run, "Scene reference image uploaded."));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UPLOADED");
        response.put("asset", asset);
        response.put("priority", safePriority);
        response.put("scene", scene);
        response.put("videoRun", hydrateVideoRunForResponse(run, safeTenantId, safeUserId));
        return response;
    }

    @Transactional
    public CreatorGenerationJob startRegenerateSceneJob(
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        scenes = enrichScenesWithStoryboardReferences(script, scenes, request);
        Map<String, Object> activeScene = activeSceneGeneration(scenes, sceneId);
        if (!activeScene.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Another shot is already generating. Wait for it to finish before starting the next one."
            );
        }
        Map<String, Object> inputPayload = copyMap(request);
        inputPayload.put("runId", runId.toString());
        inputPayload.put("scriptId", script.getId().toString());
        inputPayload.put("sceneId", sceneId);
        int sceneIndex = findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        String generationMode = generationModeFor(scene, run, request);
        String provider = providerForSceneGeneration(scene, run, request);
        String model = modelForSceneGeneration(provider, scene, run, request);
        inputPayload.put("provider", provider);
        inputPayload.put("model", model);
        inputPayload.put("generationMode", generationMode);
        boolean replacingGeneratedClip = hasClipAsset(scene);
        if (replacingGeneratedClip && !booleanValue(inputPayload.get("billingConsent"), false)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Regenerating an existing shot is a paid video-model run. Confirm the displayed provider charge and retry with billingConsent=true."
            );
        }
        scene.put("provider", provider);
        scene.put("targetProvider", provider);
        scene.put("model", model);
        scene.put("generationMode", generationMode);
        scene.put("billingMode", replacingGeneratedClip ? "PAID_SCENE_RERUN" : "INITIAL_OR_RESUMED_SCENE");
        scene.put("billingConsent", booleanValue(inputPayload.get("billingConsent"), false));
        scene.put("status", "VIDEO_GENERATION_QUEUED");
        scene.put("queuedAt", OffsetDateTime.now().toString());
        scene.put("message", "Shot queued for " + providerLabel(provider) + " generation.");
        scenes.set(sceneIndex, scene);
        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        run.put("provider", provider);
        run.put("model", model);
        run.put("status", "VIDEO_GENERATION_QUEUED");
        run.put("updatedAt", OffsetDateTime.now().toString());
        run.put("lastRegeneratedSceneId", sceneId);
        run.put("lastBillingAction", Map.of(
                "type", replacingGeneratedClip ? "PAID_SCENE_RERUN" : "INITIAL_OR_RESUMED_SCENE",
                "sceneId", sceneId,
                "billingConsent", booleanValue(inputPayload.get("billingConsent"), false),
                "recordedAt", OffsetDateTime.now().toString()
        ));
        run.put("message", "Shot " + firstText(scene.get("sceneNumber"), sceneId) + " queued for " + providerLabel(provider) + " generation.");

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_SCENE_REGENERATE,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                inputPayload
        );
        return generationJobService.updateGenerationJobProgress(
                job.getId(),
                8,
                "Queued screenplay shot video",
                outputPayload(run, "Shot " + firstText(scene.get("sceneNumber"), sceneId) + " queued for " + providerLabel(provider) + " generation.")
        );
    }

    @Transactional
    public CreatorGenerationJob runRegenerateSceneJob(
            UUID jobId,
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        long sceneStartedNanos = System.nanoTime();
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        long runLoadedNanos = System.nanoTime();
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        long scriptLoadedNanos = System.nanoTime();
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        scenes = enrichScenesWithStoryboardReferences(script, scenes, request);
        long referencesEnrichedNanos = System.nanoTime();
        int sceneIndex = findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        String generationMode = generationModeFor(scene, run, request);
        scene = localizeAiSceneForGeneration(
                script,
                scene,
                run,
                request,
                generationMode,
                jobId
        );
        scenes.set(sceneIndex, scene);
        String provider = providerForSceneGeneration(scene, run, request);
        String model = modelForSceneGeneration(provider, scene, run, request);

        scene.put("provider", provider);
        scene.put("targetProvider", provider);
        scene.put("model", model);
        scene.put("generationMode", generationMode);
        scene.put("providerRequest", buildProviderRequest(run, scene, request));
        long providerRequestBuiltNanos = System.nanoTime();
        scene.put("queuedAt", OffsetDateTime.now().toString());
        scene.put("status", "GENERATING_VIDEO");
        scenes.set(sceneIndex, scene);

        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        run.put("provider", provider);
        run.put("model", model);
        run.put("status", "GENERATING_VIDEO");
        run.put("updatedAt", OffsetDateTime.now().toString());
        run.put("message", "Generating shot " + firstText(scene.get("sceneNumber"), sceneId) + " with " + providerLabel(provider) + ".");
        run.put("lastRegeneratedSceneId", sceneId);
        run.put("lastProviderRequest", scene.get("providerRequest"));
        generationJobService.updateGenerationJobProgress(
                jobId,
                18,
                "Generating screenplay shot video",
                outputPayload(run, "Generating shot " + firstText(scene.get("sceneNumber"), sceneId) + " with " + providerLabel(provider) + ".")
        );
        long queueStatePersistedNanos = System.nanoTime();
        log.info(
                "Screenplay video scene preparation completed jobId={} runId={} sceneId={} provider={} model={} loadRunMs={} loadScriptMs={} referenceEnrichmentMs={} sceneRequestBuildMs={} queueStatePersistMs={} preparationTotalMs={} promptChars={}",
                jobId,
                runId,
                sceneId,
                provider,
                model,
                TimeUnit.NANOSECONDS.toMillis(runLoadedNanos - sceneStartedNanos),
                TimeUnit.NANOSECONDS.toMillis(scriptLoadedNanos - runLoadedNanos),
                TimeUnit.NANOSECONDS.toMillis(referencesEnrichedNanos - scriptLoadedNanos),
                TimeUnit.NANOSECONDS.toMillis(providerRequestBuiltNanos - referencesEnrichedNanos),
                TimeUnit.NANOSECONDS.toMillis(queueStatePersistedNanos - providerRequestBuiltNanos),
                TimeUnit.NANOSECONDS.toMillis(queueStatePersistedNanos - sceneStartedNanos),
                firstText(firstMap(scene.get("providerRequest")).get("prompt")).length()
        );

        try {
            String sceneObjectKey = sceneClipObjectKey(script, runId, scene);
            Map<String, Object> founderVoiceAsset = prepareFounderSceneAudio(
                    script,
                    runId,
                    jobId,
                    run,
                    scene,
                    false
            );
            if (!founderVoiceAsset.isEmpty()) {
                Map<String, Object> providerRequest = new LinkedHashMap<>(firstMap(scene.get("providerRequest")));
                String audioUrl = firstText(
                        founderVoiceAsset.get("assetUrl"),
                        founderVoiceAsset.get("signedUrl"),
                        founderVoiceAsset.get("publicUrl")
                );
                providerRequest.put("audioUrl", audioUrl);
                providerRequest.put("dialogueAudioUrl", audioUrl);
                providerRequest.put("dialogueAudioAsset", founderVoiceAsset);
                scene.put("providerRequest", providerRequest);
                scene.put("dialogueAudio", founderVoiceAsset);
                scene.put("voiceTrack", audioUrl);
                scenes.set(sceneIndex, scene);
                run.put("scenes", scenes);
                run.put("sceneClips", scenes);
            }
            long providerHandoffStartedNanos = System.nanoTime();
            ScreenplayVideoProviderGenerationService.SceneVideoRequest providerSceneRequest =
                    sceneVideoRequest(runId, script, run, scenes, sceneIndex, scene, provider, model, sceneObjectKey);
            long providerHandoffPreparedNanos = System.nanoTime();
            log.info(
                    "Screenplay video provider handoff prepared jobId={} runId={} sceneId={} targetObjectKey={} requestAssemblyMs={} elapsedSinceQueueMs={}",
                    jobId,
                    runId,
                    sceneId,
                    sceneObjectKey,
                    TimeUnit.NANOSECONDS.toMillis(providerHandoffPreparedNanos - providerHandoffStartedNanos),
                    TimeUnit.NANOSECONDS.toMillis(providerHandoffPreparedNanos - sceneStartedNanos)
            );
            ScreenplayVideoProviderGenerationService.GeneratedSceneVideo generatedVideo =
                    providerGenerationService.generate(providerSceneRequest);
            long providerGenerationCompletedNanos = System.nanoTime();
            log.info(
                    "Screenplay video provider generation completed jobId={} runId={} sceneId={} provider={} model={} operationId={} providerGenerationMs={} elapsedMs={} streamedToStorage={}",
                    jobId,
                    runId,
                    sceneId,
                    provider,
                    model,
                    generatedVideo.operationName(),
                    TimeUnit.NANOSECONDS.toMillis(providerGenerationCompletedNanos - providerHandoffPreparedNanos),
                    TimeUnit.NANOSECONDS.toMillis(providerGenerationCompletedNanos - sceneStartedNanos),
                    generatedVideo.storedObject() != null
            );
            AssetStorageService.StoredObject stored = generatedVideo.storedObject();
            if (stored == null) {
                stored = assetStorageService.uploadCreatorAsset(
                        sceneObjectKey,
                        generatedVideo.bytes(),
                        firstText(generatedVideo.contentType(), "video/mp4"),
                        SIGNED_URL_TTL
                );
            }
            long assetPersistedNanos = System.nanoTime();
            log.info(
                    "Screenplay video scene asset persisted jobId={} runId={} sceneId={} bucket={} objectKey={} sizeBytes={} storageMs={} elapsedMs={}",
                    jobId,
                    runId,
                    sceneId,
                    stored.bucket(),
                    stored.objectKey(),
                    stored.sizeBytes(),
                    TimeUnit.NANOSECONDS.toMillis(assetPersistedNanos - providerGenerationCompletedNanos),
                    TimeUnit.NANOSECONDS.toMillis(assetPersistedNanos - sceneStartedNanos)
            );
            Map<String, Object> providerMetadata = copyMap(generatedVideo.metadata());
            Map<String, Object> costMetadata = firstMap(providerMetadata.get("costMetadata"));

            scene.put("status", "VIDEO_READY");
            scene.put("providerOperationId", generatedVideo.operationName());
            scene.put("providerRequest", generatedVideo.providerRequest());
            scene.put("providerResponse", generatedVideo.providerResponse());
            scene.put("providerMetadata", providerMetadata);
            scene.put("costMetadata", costMetadata);
            scene.put("bucket", stored.bucket());
            scene.put("objectKey", stored.objectKey());
            scene.put("contentType", stored.contentType());
            scene.put("sizeBytes", stored.sizeBytes());
            scene.put("videoUrl", stored.signedUrl());
            scene.put("clipUrl", stored.signedUrl());
            scene.put("publicUrl", stored.signedUrl());
            scene.put("generatedAt", OffsetDateTime.now().toString());
            scene.put("message", "Scene video generated with " + providerLabel(provider) + " and uploaded.");
            scenes.set(sceneIndex, scene);

            recordSceneAssetSafely(new CreatorScreenplaySceneAssetService.SceneVideoUpsert(
                    script.getTenantId(),
                    script.getUserId(),
                    script.getId(),
                    runId,
                    intValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), sceneIndex + 1),
                    stored.bucket(),
                    stored.objectKey(),
                    stored.contentType(),
                    stored.sizeBytes(),
                    positiveInt(scene.get("durationSeconds"), 0),
                    provider,
                    "READY"
            ));

            run.put("scenes", scenes);
            run.put("sceneClips", scenes);
            run.put("status", allScenesHaveClips(scenes) ? "SCENE_CLIPS_READY" : "PARTIAL_SCENE_CLIPS_READY");
            run.put("updatedAt", OffsetDateTime.now().toString());
            run.put("lastGeneratedScene", compactGeneratedScene(scene));
            run.put("lastProviderResponse", generatedVideo.providerResponse());

            creatorAiService.publishProviderUsageDebit(
                    "SCREENPLAY_VIDEO_SCENE_GENERATION",
                    provider,
                    model,
                    costMetadata,
                    new CreatorAiService.AiUsageContext(
                            script.getTenantId(),
                            script.getUserId(),
                            script.getProjectId(),
                            jobId,
                            null
                    ),
                    "Generated screenplay scene video " + firstText(scene.get("sceneNumber"), sceneId)
            );
            refreshBillingSummary(run);
            long walletDebitPublishedNanos = System.nanoTime();
            CreatorGenerationJob completed = generationJobService.completeGenerationJob(jobId, outputPayload(run, "Scene video generated."));
            log.info(
                    "Screenplay video scene job completed jobId={} runId={} sceneId={} walletDebitPublishMs={} completionPersistMs={} totalElapsedMs={}",
                    jobId,
                    runId,
                    sceneId,
                    TimeUnit.NANOSECONDS.toMillis(walletDebitPublishedNanos - assetPersistedNanos),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - walletDebitPublishedNanos),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sceneStartedNanos)
            );
            return completed;
        } catch (ScreenplayVideoProviderGenerationService.ManualAvatarFallbackRequiredException ex) {
            String message = firstText(ex.getMessage(), "Local avatar generation needs manual approval before Synthesia fallback.");
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("status", "NEEDS_MANUAL_AVATAR_APPROVAL");
            fallback.put("reason", message);
            fallback.put("fallbackProvider", "synthesia");
            fallback.put("requestedProvider", provider);
            fallback.put("requestedModel", model);
            fallback.put("manualApprovalRequired", true);
            fallback.put("providerResponse", ex.providerResponse());
            fallback.put("createdAt", OffsetDateTime.now().toString());
            scene.put("status", "NEEDS_MANUAL_AVATAR_APPROVAL");
            scene.put("providerRequest", ex.providerRequest());
            scene.put("providerResponse", ex.providerResponse());
            scene.put("avatarFallback", fallback);
            scene.put("fallbackProvider", "synthesia");
            scene.put("manualApprovalRequired", true);
            scene.put("message", message);
            scene.put("updatedAt", OffsetDateTime.now().toString());
            scenes.set(sceneIndex, scene);
            run.put("scenes", scenes);
            run.put("sceneClips", scenes);
            run.put("status", "WAITING_FOR_MANUAL_AVATAR_APPROVAL");
            run.put("manualApprovalRequired", true);
            run.put("manualApprovalSceneId", sceneId);
            run.put("avatarFallback", fallback);
            run.put("updatedAt", OffsetDateTime.now().toString());
            run.put("message", message);
            log.warn("Screenplay local avatar generation paused for manual fallback approval jobId={} runId={} sceneId={} provider={} model={} reason={}",
                    jobId, runId, sceneId, provider, model, message);
            return generationJobService.completeGenerationJob(jobId, outputPayload(run, message));
        } catch (RuntimeException ex) {
            String rawMessage = defaultString(ex.getMessage(), ex.getClass().getSimpleName());
            boolean contentPolicyViolation = isContentPolicyViolation(ex);
            String status = contentPolicyViolation ? "CONTENT_POLICY_REJECTED" : "VIDEO_FAILED";
            String message = contentPolicyViolation
                    ? "The provider rejected this scene's output for its content policy (not a system error) - "
                            + "adjust the prompt/direction for this shot and regenerate. Provider detail: " + rawMessage
                    : rawMessage;
            if (contentPolicyViolation) {
                log.warn("Screenplay scene video rejected by provider content policy jobId={} runId={} sceneId={} provider={} model={} elapsedMs={} providerDetail={}",
                        jobId, runId, sceneId, provider, model,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sceneStartedNanos), rawMessage);
            } else {
                log.error("Screenplay scene video generation failed jobId={} runId={} sceneId={} provider={} model={} elapsedMs={} errorType={} errorMessage={}",
                        jobId, runId, sceneId, provider, model,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sceneStartedNanos),
                        ex.getClass().getSimpleName(), rawMessage, ex);
            }
            scene.put("status", status);
            scene.put("errorMessage", message);
            scene.put("contentPolicyViolation", contentPolicyViolation);
            scene.put("failedAt", OffsetDateTime.now().toString());
            scenes.set(sceneIndex, scene);
            run.put("scenes", scenes);
            run.put("sceneClips", scenes);
            run.put("status", status);
            run.put("updatedAt", OffsetDateTime.now().toString());
            run.put("message", message);
            generationJobService.failGenerationJob(jobId, message, outputPayload(run, message));
            throw new ResponseStatusException(
                    contentPolicyViolation ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY, message, ex
            );
        }
    }

    /**
     * Best-effort dual-write to the normalized creator_assets scene/combined-video rows.
     * The JSONB run.scenes[] write these calls sit next to remains the code every other
     * method in this class already reads/writes - this only adds a reliable, queryable
     * mirror for the video page and the accept/reject workflow. A failure here must never
     * fail an otherwise-successful scene generation or final render.
     */
    private void recordSceneAssetSafely(CreatorScreenplaySceneAssetService.SceneVideoUpsert upsert) {
        try {
            sceneAssetService.recordSceneVideo(upsert);
        } catch (RuntimeException ex) {
            log.warn("Could not record normalized scene asset runId={} shotNumber={} errorType={} errorMessage={}",
                    upsert.runId(), upsert.shotNumber(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void recordCombinedVideoAssetSafely(CreatorScreenplaySceneAssetService.CombinedVideoUpsert upsert) {
        try {
            sceneAssetService.recordCombinedVideo(upsert);
        } catch (RuntimeException ex) {
            log.warn("Could not record normalized combined video asset runId={} errorType={} errorMessage={}",
                    upsert.runId(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    // fal.ai (and providers behind it, e.g. seedance) return HTTP 422 with
    // type=content_policy_violation when generated output - not the request itself -
    // trips their safety filter (seen in practice on generated audio). That's an
    // expected, non-retryable provider decision on this specific input, not an
    // infrastructure failure, so it shouldn't surface identically to a real 502/timeout.
    private boolean isContentPolicyViolation(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String text = defaultString(current.getMessage(), "").toLowerCase(Locale.ROOT);
            if (text.contains("content_policy_violation") || text.contains("partner_validation_failed")) {
                return true;
            }
        }
        return false;
    }

    Map<String, Object> prepareFounderSceneAudio(
            CreatorScript script,
            UUID runId,
            UUID jobId,
            Map<String, Object> run,
            Map<String, Object> scene,
            boolean allowGeneration
    ) {
        return new FounderSceneAudioCloner(this, creatorAiService, voiceGenerationService)
                .prepareFounderSceneAudio(script, runId, jobId, run, scene, allowGeneration);
    }

    @Transactional
    public CreatorGenerationJob startFinalRenderJob(
            UUID runId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        Map<String, Object> inputPayload = copyMap(request);
        inputPayload.put("runId", runId.toString());
        inputPayload.put("scriptId", script.getId().toString());

        return generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_FINAL_RENDER,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                inputPayload
        );
    }

    @Transactional
    public CreatorGenerationJob startAudioPackJob(
            UUID runId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        Map<String, Object> inputPayload = copyMap(request);
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> founderProfile = founderAvatarProfile(inputPayload, run, scriptPayload);
        applyAudioRequestType(inputPayload);
        inputPayload.put("runId", runId.toString());
        inputPayload.put("scriptId", script.getId().toString());
        inputPayload.put("mode", "SCREENPLAY_AUDIO_PACK");
        inputPayload.putIfAbsent("founderAvatarProfile", founderProfile);
        inputPayload.putIfAbsent("founderKit", founderProfile);
        inputPayload.put("voiceProvider", firstText(
                inputPayload.get("voiceProvider"),
                inputPayload.get("ttsProvider"),
                voiceProviderFrom(inputPayload.get("provider")),
                run.get("voiceProvider"),
                scriptPayload.get("voiceProvider"),
                avatarVoiceProvider(founderProfile),
                "google_chirp"
        ));
        inputPayload.putIfAbsent("generateDialogue", firstValue(inputPayload.get("generateVoice"), true));
        inputPayload.putIfAbsent("musicSource", normalizeMusicSource(firstText(
                inputPayload.get("musicSource"),
                inputPayload.get("backgroundMusicSource"),
                "free_licensed"
        )));
        inputPayload.putIfAbsent("musicProvider", firstText(inputPayload.get("musicProvider"), "google_lyria"));
        Map<String, Object> dialogueVoiceProfile = dialogueVoiceProfile(inputPayload, run, scriptPayload);
        inputPayload.putAll(dialogueVoiceProfile);

        return generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_AUDIO_PACK,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                inputPayload
        );
    }

    @Transactional
    public CreatorGenerationJob runAudioPackJob(
            UUID jobId,
            UUID runId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        Map<String, Object> inputPayload = copyMap(request);
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> founderProfile = founderAvatarProfile(inputPayload, run, scriptPayload);
        applyAudioRequestType(inputPayload);
        inputPayload.putIfAbsent("founderAvatarProfile", founderProfile);
        inputPayload.putIfAbsent("founderKit", founderProfile);
        inputPayload.put("voiceProvider", firstText(
                inputPayload.get("voiceProvider"),
                inputPayload.get("ttsProvider"),
                voiceProviderFrom(inputPayload.get("provider")),
                run.get("voiceProvider"),
                scriptPayload.get("voiceProvider"),
                avatarVoiceProvider(founderProfile),
                "google_chirp"
        ));
        Map<String, Object> audioProductionPlan = firstMap(inputPayload.get("audioProductionPlan"), run.get("audioProductionPlan"));
        Map<String, Object> soundDesignPlan = firstMap(inputPayload.get("soundDesignPlan"), run.get("soundDesignPlan"));
        Map<String, Object> editingPlan = firstMap(inputPayload.get("editorHandoffPlan"), inputPayload.get("editingPlan"), run.get("editorHandoffPlan"), run.get("editingPlan"));
        String captionText = audioPackVoiceText(inputPayload, run, script);
        String voiceText = firstText(founderProfile.get("spokenText"));
        if (voiceText.isBlank()) {
            voiceText = applyPronunciationGuide(captionText, firstText(founderProfile.get("pronunciationGuide")));
        }
        Map<String, Object> dialogueVoiceProfile = dialogueVoiceProfile(inputPayload, run, scriptPayload);
        inputPayload.putAll(dialogueVoiceProfile);
        run.put("dialogueVoiceProfile", dialogueVoiceProfile);
        String musicPrompt = audioPackMusicPrompt(inputPayload, audioProductionPlan, soundDesignPlan, run);
        if (!musicPrompt.isBlank()) {
            audioProductionPlan.put("backgroundMusicPrompt", musicPrompt);
            soundDesignPlan.put("backgroundMusicPrompt", musicPrompt);
            editingPlan.put("backgroundMusicPrompt", musicPrompt);
            run.put("backgroundMusicPrompt", musicPrompt);
        }
        boolean preserveExistingMusic = booleanValue(inputPayload.get("preserveExistingMusic"), false);
        String musicSource = normalizeMusicSource(firstText(
                inputPayload.get("musicSource"),
                inputPayload.get("backgroundMusicSource"),
                mapValue(audioProductionPlan.get("music")).get("source"),
                mapValue(soundDesignPlan.get("backgroundMusic")).get("source"),
                "free_licensed"
        ));
        if (preserveExistingMusic) {
            musicSource = "preserve";
        }
        if ("none".equals(firstText(mapValue(audioProductionPlan.get("music")).get("mode"), mapValue(soundDesignPlan.get("backgroundMusic")).get("mode")))
                && firstText(inputPayload.get("musicSource"), inputPayload.get("backgroundMusicSource")).isBlank()) {
            musicSource = "none";
        }
        boolean generateDialogue = booleanValue(firstValue(inputPayload.get("generateDialogue"), inputPayload.get("generateVoice")), true);
        boolean forceRegenerateDialogue = booleanValue(firstValue(inputPayload.get("forceRegenerateDialogue"), inputPayload.get("forceRegenerateVoice")), false);
        boolean aiMusicRequested = "ai_generated".equals(musicSource) || booleanValue(inputPayload.get("generateAiMusic"), false);
        Object generateMusicValue = firstValue(inputPayload.get("generateMusic"), inputPayload.get("generateAiMusic"));
        boolean generateMusic = generateMusicValue == null ? aiMusicRequested : booleanValue(generateMusicValue, aiMusicRequested);
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                jobId,
                null
        );
        Map<String, Object> exactFounderAudio = firstMap(
                founderProfile.get("exactFounderAudioAsset"),
                founderProfile.get("finalFounderAudioAsset")
        );
        String selectedVoiceProvider = firstText(inputPayload.get("voiceProvider"), "google_chirp");
        if (generateDialogue
                && "dalai_llama".equalsIgnoreCase(selectedVoiceProvider)
                && !"APPROVED".equalsIgnoreCase(firstText(founderProfile.get("voiceApprovalStatus")))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Approve the 10-second founder voice preview or upload exact founder audio before generating the full avatar video."
            );
        }

        generationJobService.updateGenerationJobProgress(
                jobId,
                15,
                "Preparing dialogue and background music",
                outputPayload(run, "Preparing dialogue voiceover and background music plan.")
        );

        List<Map<String, Object>> assets = new ArrayList<>();
        Map<String, Object> dialogue = new LinkedHashMap<>();
        String dialogueFingerprint = dialogueFingerprint(voiceText, inputPayload, run);
        Map<String, Object> existingDialogueAudio = firstMap(run.get("dialogueAudio"));
        String existingDialogueFingerprint = firstText(
                firstMap(existingDialogueAudio.get("metadata")).get("dialogueFingerprint"),
                existingDialogueAudio.get("dialogueFingerprint")
        );
        if (generateDialogue && !exactFounderAudio.isEmpty()) {
            String exactAudioUrl = firstText(
                    exactFounderAudio.get("assetUrl"),
                    exactFounderAudio.get("signedUrl"),
                    exactFounderAudio.get("publicUrl"),
                    founderProfile.get("finalFounderAudioUrl")
            );
            assets.add(exactFounderAudio);
            run.put("voiceTrack", exactAudioUrl);
            run.put("dialogueAudio", exactFounderAudio);
            dialogue.put("status", "FOUNDER_AUDIO");
            dialogue.put("asset", exactFounderAudio);
            dialogue.put("dialogueFingerprint", dialogueFingerprint);
            generationJobService.updateGenerationJobProgress(jobId, 55, "Founder audio ready", outputPayload(run, "Using the founder's approved final recording."));
        } else if (generateDialogue && !voiceText.isBlank()
                && !forceRegenerateDialogue
                && !existingDialogueAudio.isEmpty()
                && dialogueFingerprint.equals(existingDialogueFingerprint)) {
            assets.add(existingDialogueAudio);
            dialogue.put("status", "REUSED");
            dialogue.put("asset", existingDialogueAudio);
            dialogue.put("dialogueFingerprint", dialogueFingerprint);
            generationJobService.updateGenerationJobProgress(jobId, 42, "Dialogue already current", outputPayload(run, "Existing dialogue voiceover matches the approved script."));
        } else if (generateDialogue && !voiceText.isBlank()) {
            creatorAiService.assertWalletBalanceForModelRun("SCREENPLAY_AUDIO_VOICE_GENERATE", usageContext);
            Map<String, Object> voiceOptions = new LinkedHashMap<>();
            voiceOptions.put("provider", firstText(inputPayload.get("voiceProvider"), inputPayload.get("provider"), "google_chirp"));
            voiceOptions.put("founderAvatarProfile", founderProfile);
            voiceOptions.put("founderKit", founderProfile);
            voiceOptions.put("avatarId", firstText(inputPayload.get("avatarId"), run.get("avatarId"), founderProfile.get("avatarId")));
            voiceOptions.put("voiceId", firstText(inputPayload.get("voiceId"), run.get("voiceId"), founderProfile.get("voiceId")));
            voiceOptions.put("voiceEmbeddingId", firstText(inputPayload.get("voiceEmbeddingId"), run.get("voiceEmbeddingId"), founderProfile.get("voiceEmbeddingId")));
            voiceOptions.put("localModels", firstMap(inputPayload.get("localModels"), inputPayload.get("localAvatarModels"), founderProfile.get("localModels")));
            voiceOptions.put("voiceProfileId", firstText(
                    inputPayload.get("voiceProfileId"),
                    founderProfile.get("voiceProfileId"),
                    firstMap(founderProfile.get("localModels")).get("voiceProfileId")
            ));
            voiceOptions.put("spokenText", voiceText);
            voiceOptions.put("captionText", captionText);
            voiceOptions.put("pronunciationGuide", firstText(founderProfile.get("pronunciationGuide")));
            voiceOptions.put("promptText", firstText(founderProfile.get("referenceTranscript")));
            voiceOptions.put("referenceTranscript", firstText(founderProfile.get("referenceTranscript")));
            voiceOptions.put("elevenLabsVoiceId", firstText(founderProfile.get("elevenLabsVoiceId")));
            voiceOptions.put("sarvamVoiceId", firstText(founderProfile.get("sarvamVoiceId")));
            putIfBlank(voiceOptions, "voiceName", firstText(inputPayload.get("voiceName"), inputPayload.get("voice")));
            putIfBlank(voiceOptions, "language", firstText(inputPayload.get("dialogueLanguage"), inputPayload.get("language"), run.get("dialogueLanguage"), founderProfile.get("language")));
            putIfBlank(voiceOptions, "languageCode", firstText(inputPayload.get("languageCode"), run.get("languageCode"), founderProfile.get("languageCode"), languageCodeFor(firstText(run.get("dialogueLanguage")))));
            putIfBlank(voiceOptions, "voiceGender", dialogueVoiceProfile.get("voiceGender"));
            putIfBlank(voiceOptions, "speakerName", dialogueVoiceProfile.get("speakerName"));
            log.info("Generating screenplay dialogue audio scriptId={} runId={} gender={} speaker={} provider={}",
                    script.getId(),
                    runId,
                    firstText(dialogueVoiceProfile.get("voiceGender"), "unspecified"),
                    firstText(dialogueVoiceProfile.get("speakerName"), "narrator"),
                    firstText(voiceOptions.get("provider")));
            GoogleChirpVoiceGenerationService.GeneratedVoice voice = voiceGenerationService.generateVoice(
                    voiceText,
                    voiceOptions
            );
            Map<String, Object> voiceMetadata = new LinkedHashMap<>(voice.metadata());
            voiceMetadata.put("dialogueFingerprint", dialogueFingerprint);
            voiceMetadata.put("dialogueSource", "approved_screenplay_or_srt");
            voiceMetadata.put("regeneratedBecause", forceRegenerateDialogue ? "forced_by_user" : "dialogue_text_or_voice_settings_changed");
            voiceMetadata.put("dialogueVoiceProfile", dialogueVoiceProfile);
            Map<String, Object> voiceAsset = storeAudioAsset(
                    script,
                    runId,
                    voice.bytes(),
                    voice.contentType(),
                    "voiceover",
                    voiceMetadata,
                    voice.providerRequest(),
                    voice.providerResponse()
            );
            assets.add(voiceAsset);
            run.put("voiceTrack", voiceAsset.get("assetUrl"));
            run.put("dialogueAudio", voiceAsset);
            dialogue.put("status", "GENERATED");
            dialogue.put("asset", voiceAsset);
            dialogue.put("dialogueFingerprint", dialogueFingerprint);
            creatorAiService.publishProviderUsageDebit(
                    "SCREENPLAY_AUDIO_VOICE_GENERATE",
                    firstText(voiceMetadata.get("provider"), "google_chirp"),
                    firstText(voiceMetadata.get("model"), voiceMetadata.get("voiceName"), "google_chirp"),
                    firstMap(voiceMetadata.get("costMetadata")),
                    usageContext,
                    "Generated screenplay voiceover audio"
            );
            generationJobService.updateGenerationJobProgress(jobId, 55, "Dialogue voiceover generated", outputPayload(run, "Dialogue voiceover generated."));
        } else if (!existingDialogueAudio.isEmpty()) {
            assets.add(existingDialogueAudio);
            dialogue.put("status", generateDialogue ? "NO_DIALOGUE_TEXT_REUSED_EXISTING" : "SKIPPED_REUSED_EXISTING");
            dialogue.put("asset", existingDialogueAudio);
        } else {
            dialogue.put("status", generateDialogue ? "NO_DIALOGUE_TEXT" : "SKIPPED");
            dialogue.put("reason", generateDialogue ? "No dialogue text was available from screenplay/SRT." : "Dialogue generation was not requested.");
        }

        Map<String, Object> backgroundMusic = new LinkedHashMap<>();
        if ("preserve".equals(musicSource)) {
            Map<String, Object> existingMusicAsset = generatedMusicAsset(run);
            if (!existingMusicAsset.isEmpty()) {
                assets.add(existingMusicAsset);
                run.put("musicTrack", firstText(
                        existingMusicAsset.get("assetUrl"),
                        existingMusicAsset.get("signedUrl"),
                        existingMusicAsset.get("publicUrl")
                ));
                run.put("backgroundMusic", existingMusicAsset);
                backgroundMusic.put("status", "REUSED");
                backgroundMusic.put("sourceType", "ai_generated");
                backgroundMusic.put("asset", existingMusicAsset);
                backgroundMusic.put("prompt", firstText(run.get("backgroundMusicPrompt"), musicPrompt));
            } else {
                backgroundMusic.put("status", "SKIPPED");
                backgroundMusic.put("sourceType", "preserve");
                backgroundMusic.put("reason", "No generated background music exists for this run yet.");
            }
        } else if ("free_licensed".equals(musicSource)) {
            Map<String, Object> freeMusicSelectionPlan = freeMusicSelectionPlan(inputPayload, audioProductionPlan, soundDesignPlan, run, musicPrompt);
            backgroundMusic.putAll(freeMusicSelectionPlan);
            audioProductionPlan.put("freeMusicSelectionPlan", freeMusicSelectionPlan);
            audioProductionPlan.put("freeMusicPlan", freeMusicSelectionPlan);
            editingPlan.put("freeMusicSelectionPlan", freeMusicSelectionPlan);
            editingPlan.put("musicLicenseRequirement", freeMusicSelectionPlan.get("licensePolicy"));
            run.put("freeMusicSelectionPlan", freeMusicSelectionPlan);
            run.put("backgroundMusicSelection", freeMusicSelectionPlan);
            run.remove("backgroundMusic");
            run.remove("musicTrack");
        } else if ("ai_generated".equals(musicSource) && generateMusic && !musicPrompt.isBlank()) {
            creatorAiService.assertWalletBalanceForModelRun("SCREENPLAY_AUDIO_MUSIC_GENERATE", usageContext);
            double durationSeconds = Math.max(4.0, Math.min(90.0, positiveInt(firstValue(inputPayload.get("durationSeconds"), run.get("durationSeconds")), 60)));
            GoogleLyriaMusicGenerationService.GeneratedAudio music = musicGenerationService.generateMusic(
                    musicPrompt,
                    firstText(inputPayload.get("negativePrompt"), "vocals, lyrics, copyrighted melodies, famous songs, artist imitation"),
                    firstText(inputPayload.get("musicLayerType"), "music"),
                    durationSeconds
            );
            Map<String, Object> musicAsset = storeAudioAsset(
                    script,
                    runId,
                    music.bytes(),
                    music.contentType(),
                    "music-bed",
                    music.metadata(),
                    music.providerRequest(),
                    music.providerResponse()
            );
            assets.add(musicAsset);
            run.put("musicTrack", musicAsset.get("assetUrl"));
            run.put("backgroundMusic", musicAsset);
            run.remove("backgroundMusicSelection");
            backgroundMusic.put("status", "GENERATED");
            backgroundMusic.put("sourceType", "ai_generated");
            backgroundMusic.put("asset", musicAsset);
            backgroundMusic.put("prompt", musicPrompt);
            creatorAiService.publishProviderUsageDebit(
                    "SCREENPLAY_AUDIO_MUSIC_GENERATE",
                    firstText(music.metadata().get("provider"), "google_lyria"),
                    firstText(music.metadata().get("model"), "lyria"),
                    firstMap(music.metadata().get("costMetadata")),
                    usageContext,
                    "Generated screenplay background music audio"
            );
        } else if ("none".equals(musicSource)) {
            backgroundMusic.put("status", "SKIPPED");
            backgroundMusic.put("sourceType", "none");
            backgroundMusic.put("reason", "No background music requested.");
            run.remove("backgroundMusicSelection");
            run.remove("backgroundMusic");
            run.remove("musicTrack");
        } else {
            backgroundMusic.put("status", generateMusic ? "NO_MUSIC_PROMPT" : "SKIPPED");
            backgroundMusic.put("sourceType", musicSource);
            backgroundMusic.put("reason", generateMusic ? "No music prompt was available." : "AI music generation was not requested.");
        }

        Map<String, Object> audioPack = new LinkedHashMap<>();
        audioPack.put("status", "READY");
        audioPack.put("assets", assets);
        audioPack.put("dialogue", dialogue);
        audioPack.put("backgroundMusic", backgroundMusic);
        audioPack.put("musicSource", musicSource);
        audioPack.put("dialogueFingerprint", dialogueFingerprint);
        audioPack.put("voiceText", voiceText);
        audioPack.put("musicPrompt", musicPrompt);
        audioPack.put("audioMixStandards", audioMixStandards(run.get("audioMixStandards"), soundDesignPlan.get("audioMixStandards")));
        audioPack.put("generatedAt", OffsetDateTime.now().toString());
        audioProductionPlan.put("generatedAudioAssets", assets);
        audioProductionPlan.put("dialogue", dialogue);
        audioProductionPlan.put("backgroundMusic", backgroundMusic);
        audioProductionPlan.put("audioPack", audioPack);
        editingPlan.put("generatedAudioAssets", assets);
        editingPlan.put("dialogue", dialogue);
        editingPlan.put("backgroundMusic", backgroundMusic);
        editingPlan.put("audioPack", audioPack);
        run.put("audioProductionPlan", audioProductionPlan);
        run.put("audioPack", audioPack);
        run.put("audioAssets", assets);
        run.put("editingPlan", editingPlan);
        run.put("editorHandoffPlan", editingPlan);
        run.put("status", assets.isEmpty() ? "AUDIO_PLAN_READY" : "AUDIO_READY");
        run.put("message", audioCompletionMessage(dialogue, backgroundMusic, assets));
        run.put("updatedAt", OffsetDateTime.now().toString());

        if (!assets.isEmpty() && allScenesHaveClips(mapListValue(run.get("scenes")))) {
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    82,
                    "Audio saved. Updating the final video preview.",
                    outputPayload(run, "Audio saved. Updating the final video preview.")
            );
            try {
                Map<String, Object> automaticRenderRequest = automaticFinalRenderRequest(run, inputPayload, "audio_pack");
                CreatorGenerationJob finalJob = startFinalRenderJob(runId, automaticRenderRequest, safeTenantId, safeUserId);
                run.put("automaticFinalRenderJobId", finalJob.getId().toString());
                run.put("automaticFinalRenderStatus", "RUNNING");
                generationJobService.updateGenerationJobProgress(
                        jobId,
                        90,
                        "Combining the latest dialogue and music into the final preview.",
                        outputPayload(run, "Combining the latest dialogue and music into the final preview.")
                );
                CreatorGenerationJob completedFinalJob = runFinalRenderJob(
                        finalJob.getId(),
                        runId,
                        automaticRenderRequest,
                        safeTenantId,
                        safeUserId
                );
                RunRecord mergedRecord = loadRun(runId, safeTenantId, safeUserId);
                run = copyMap(mergedRecord.run());
                run.put("automaticFinalRenderJobId", finalJob.getId().toString());
                run.put("automaticFinalRenderStatus", completedFinalJob.getStatus());
                run.put("message", "Audio assets saved and final preview updated.");
            } catch (RuntimeException ex) {
                run.put("automaticFinalRenderStatus", "FAILED");
                run.put("automaticFinalRenderError", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                run.put("message", "Audio assets are ready. The final preview can be re-merged from this screen.");
                log.warn("Automatic final merge failed after audio pack scriptId={} runId={} errorType={} errorMessage={}",
                        script.getId(), runId, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        refreshBillingSummary(run);
        return generationJobService.completeGenerationJob(jobId, outputPayload(run, stringValue(run.get("message"), "Dialogue and background music prepared.")));
    }

    private Map<String, Object> automaticFinalRenderRequest(
            Map<String, Object> run,
            Map<String, Object> request,
            String trigger
    ) {
        Map<String, Object> automaticRequest = copyMap(request);
        automaticRequest.put("renderMode", "MERGE_SCENE_CLIPS");
        automaticRequest.put("requireAcceptedScenes", false);
        automaticRequest.put("autoTriggeredBy", trigger);
        Object targetDuration = firstValue(
                automaticRequest.get("targetDurationSeconds"),
                automaticRequest.get("durationSeconds"),
                run == null ? null : run.get("targetDurationSeconds"),
                run == null ? null : run.get("durationSeconds")
        );
        if (targetDuration != null) {
            automaticRequest.put("targetDurationSeconds", targetDuration);
        }
        return automaticRequest;
    }

    @Transactional
    public CreatorGenerationJob runFinalRenderJob(
            UUID jobId,
            UUID runId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        RunRecord record = loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        Map<String, Object> inputPayload = copyMap(request);
        inputPayload.put("runId", runId.toString());
        inputPayload.put("scriptId", script.getId().toString());
        generationJobService.updateGenerationJobProgress(jobId, 18, "Merging generated screenplay scene clips", Map.of(
                "runId", runId.toString(),
                "scriptId", script.getId().toString()
        ));

        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        List<String> acceptedSceneIds = firstList(inputPayload.get("acceptedSceneIds")).stream()
                .map(value -> stringValue(value, ""))
                .filter(value -> !value.isBlank())
                .toList();
        boolean requireAcceptedScenes = booleanValue(inputPayload.get("requireAcceptedScenes"), false);
        if (requireAcceptedScenes && !finalVideoRenderer().acceptedScenesCoverAll(scenes, acceptedSceneIds)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Accept every generated scene clip before combining the final video."
            );
        }
        List<Map<String, Object>> mergeInputs = scenes.stream()
                .map(scene -> {
                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("sceneId", stringValue(scene.get("id"), stringValue(scene.get("sceneId"), "")));
                    input.put("sceneNumber", intValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), 0));
                    input.put("durationSeconds", positiveInt(scene.get("durationSeconds"), 0));
                    input.put("videoUrl", firstText(scene.get("videoUrl"), scene.get("clipUrl"), scene.get("publicUrl")));
                    input.put("bucket", firstText(scene.get("bucket")));
                    input.put("objectKey", firstText(scene.get("objectKey")));
                    input.put("contentType", firstText(scene.get("contentType"), "video/mp4"));
                    return input;
                })
                .toList();
        Map<String, Object> renderManifest = copyMap(run.get("renderManifest"));
        renderManifest.put("renderMode", firstText(inputPayload.get("renderMode"), "MERGE_SCENE_CLIPS"));
        renderManifest.put("mergeInputs", mergeInputs);
        Map<String, Object> videoFinishingPlan = withAudioMixStandards(firstMap(inputPayload.get("videoFinishingPlan"), run.get("videoFinishingPlan")));
        Map<String, Object> soundDesignPlan = withAudioMixStandards(firstMap(inputPayload.get("soundDesignPlan"), run.get("soundDesignPlan")));
        Map<String, Object> imageLedAdPlan = imageLedAdPlan(inputPayload, run, videoFinishingPlan);
        Map<String, Object> audioProductionPlan = audioProductionPlan(inputPayload, run, soundDesignPlan, videoFinishingPlan);
        Map<String, Object> editingPlan = editingPlan(inputPayload, run, videoFinishingPlan, soundDesignPlan, imageLedAdPlan, audioProductionPlan);
        renderManifest.put("videoFinishingPlan", videoFinishingPlan);
        renderManifest.put("soundDesignPlan", soundDesignPlan);
        renderManifest.put("imageLedAdPlan", imageLedAdPlan);
        renderManifest.put("audioProductionPlan", audioProductionPlan);
        renderManifest.put("editingPlan", editingPlan);
        renderManifest.put("editorHandoffPlan", editingPlan);
        renderManifest.put("recommendedEditingTools", firstValue(editingPlan.get("recommendedTools"), editingPlan.get("toolsToUse")));
        renderManifest.put("audioMixStandards", audioMixStandards(videoFinishingPlan.get("audioMixStandards"), soundDesignPlan.get("audioMixStandards"), run.get("audioMixStandards"), inputPayload.get("audioMixStandards")));
        renderManifest.put("srtFile", firstMap(run.get("srtFile"), script.getScriptPayload() == null ? null : script.getScriptPayload().get("srtFile")));
        renderManifest.put("acceptedSceneIds", acceptedSceneIds);
        renderManifest.put("requireAcceptedScenes", requireAcceptedScenes);
        renderManifest.put("preparedAt", OffsetDateTime.now().toString());
        FinalVideoRenderer finalVideoRenderer = finalVideoRenderer();
        renderManifest.put("readyForMerge", mergeInputs.stream().allMatch(finalVideoRenderer::hasMergeableClip));

        if (Boolean.TRUE.equals(renderManifest.get("readyForMerge"))) {
            Map<String, Object> finalVideo = finalVideoRenderer.mergeSceneClips(script, runId, scenes, run, inputPayload);
            List<Map<String, Object>> finalVideoVariants = mapListValue(finalVideo.get("variants"));
            if (!finalVideoVariants.isEmpty()) {
                run.put("finalVideoVariants", finalVideoVariants);
                renderManifest.put("finalVideoVariants", finalVideoVariants);
                renderManifest.put("defaultAudioVariant", firstText(finalVideo.get("audioVariant"), "VIDEO_GENERATED_AUDIO"));
            }
            // The merge above already produced a real, storable video. A wallet/billing
            // failure here is a separate concern from that merge and must not discard it -
            // catch it, record it for reconciliation, and still persist the finished video
            // below. Letting it propagate would abort before completeGenerationJob() ever
            // runs, leaving the job's output stuck on its early sparse progress payload
            // (just runId/scriptId) - which then poisons every "latest run" lookup for this
            // script into reporting no video exists, even though one was just rendered.
            Map<String, Object> packageBilling;
            try {
                packageBilling = chargeAiShortStarterPackageIfNeeded(script, runId, scenes, run);
            } catch (RuntimeException ex) {
                log.error("Creator video package billing failed after a successful merge runId={} scriptId={} errorType={} errorMessage={}",
                        runId, script.getId(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
                packageBilling = new LinkedHashMap<>();
                packageBilling.put("packageCode", "AI_SHORT_STARTER_60");
                packageBilling.put("status", "DEBIT_FAILED");
                packageBilling.put("errorMessage", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                packageBilling.put("failedAt", OffsetDateTime.now().toString());
            }
            if (!packageBilling.isEmpty()) {
                finalVideo.put("packageBilling", packageBilling);
                renderManifest.put("packageBilling", packageBilling);
                run.put("packageBilling", packageBilling);
            }
            finalVideo.put("imageLedAdPlan", imageLedAdPlan);
            finalVideo.put("audioProductionPlan", audioProductionPlan);
            finalVideo.put("editingPlan", editingPlan);
            finalVideo.put("editorHandoffPlan", editingPlan);
            finalVideo.put("recommendedEditingTools", firstValue(editingPlan.get("recommendedTools"), editingPlan.get("toolsToUse")));
            renderManifest.put("finalVideo", finalVideo);
            renderManifest.put("finalVideoUrl", finalVideo.get("videoUrl"));
            renderManifest.put("finalObjectKey", finalVideo.get("objectKey"));
            renderManifest.put("renderer", "local_ffmpeg_concat");
            renderManifest.put("renderedAt", OffsetDateTime.now().toString());
            run.put("finalVideo", finalVideo);
            run.put("videoUrl", finalVideo.get("videoUrl"));
            run.put("publicUrl", finalVideo.get("videoUrl"));
            run.put("finalVideoUrl", finalVideo.get("videoUrl"));
            run.put("status", "VIDEO_READY");
            run.put("message", "Final video rendered and uploaded.");

            int totalDurationSeconds = scenes.stream()
                    .mapToInt(scene -> positiveInt(scene.get("durationSeconds"), 0))
                    .sum();
            recordCombinedVideoAssetSafely(new CreatorScreenplaySceneAssetService.CombinedVideoUpsert(
                    script.getTenantId(),
                    script.getUserId(),
                    script.getId(),
                    runId,
                    stringValue(finalVideo.get("bucket"), ""),
                    stringValue(finalVideo.get("objectKey"), ""),
                    stringValue(finalVideo.get("contentType"), "video/mp4"),
                    longValue(finalVideo.get("sizeBytes"), 0),
                    totalDurationSeconds,
                    "READY"
            ));
        } else {
            run.put("status", "WAITING_FOR_SCENE_CLIPS");
            run.put("message", "Final render manifest prepared, but scene MP4 object keys are still missing.");
        }

        refreshBillingSummary(run);
        run.put("renderManifest", renderManifest);
        run.put("updatedAt", OffsetDateTime.now().toString());
        return generationJobService.completeGenerationJob(jobId, outputPayload(run, stringValue(run.get("message"), "Final render manifest prepared.")));
    }

    private Map<String, Object> chargeAiShortStarterPackageIfNeeded(
            CreatorScript script,
            UUID runId,
            List<Map<String, Object>> scenes,
            Map<String, Object> run
    ) {
        return videoGenerationBiller().chargeAiShortStarterPackageIfNeeded(script, runId, scenes, run);
    }

    private void refreshBillingSummary(Map<String, Object> run) {
        videoGenerationBiller().refreshBillingSummary(run);
    }

    private VideoGenerationBiller videoGenerationBiller() {
        return new VideoGenerationBiller(
                billingWalletService,
                aiShortStarterPriceInr,
                packageUsdInrRate,
                usageMarkupPercent,
                videoUsageMarkupPercent
        );
    }


    private ScreenplayVideoProviderGenerationService.SceneVideoRequest sceneVideoRequest(
            UUID runId,
            CreatorScript script,
            Map<String, Object> run,
            List<Map<String, Object>> scenes,
            int sceneIndex,
            Map<String, Object> scene,
            String provider,
            String model,
            String targetObjectKey
    ) {
        Map<String, Object> providerRequest = firstMap(scene.get("providerRequest"));
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> previousScene = sceneIndex > 0 ? compactScene(scenes.get(sceneIndex - 1)) : Map.of();
        Map<String, Object> nextScene = sceneIndex + 1 < scenes.size() ? compactScene(scenes.get(sceneIndex + 1)) : Map.of();
        List<Object> runSrtCues = firstList(run.get("srtCues"), scriptPayload.get("srtCues"));
        List<Map<String, Object>> sceneSrtCues = srtCuesForScene(runSrtCues, scene);
        if (sceneSrtCues.isEmpty()) {
            sceneSrtCues = fallbackDialogueCuesForScene(scene);
        }
        return new ScreenplayVideoProviderGenerationService.SceneVideoRequest(
                runId.toString(),
                script.getId().toString(),
                firstText(scene.get("id"), scene.get("sceneId")),
                intValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), sceneIndex + 1),
                provider,
                model,
                firstText(providerRequest.get("prompt"), scene.get("providerPrompt"), scene.get("prompt"), scene.get("seedancePrompt")),
                positiveInt(firstValue(providerRequest.get("durationSeconds"), scene.get("durationSeconds")), 15),
                firstText(providerRequest.get("aspectRatio"), "9:16"),
                firstText(scene.get("generationMode"), "ai_generated"),
                longValue(providerRequest.get("seriesSeed"), 0),
                longValue(providerRequest.get("seed"), 0),
                run,
                scene,
                providerRequest,
                firstMap(providerRequest.get("videoConsistencyBible"), run.get("videoConsistencyBible"), scriptPayload.get("videoConsistencyBible")),
                firstMap(providerRequest.get("seedancePromptStrategy"), run.get("seedancePromptStrategy"), scriptPayload.get("seedancePromptStrategy")),
                firstMap(providerRequest.get("videoPacingProfile"), run.get("videoPacingProfile"), scriptPayload.get("videoPacingProfile")),
                new ArrayList<Object>(sceneSrtCues),
                previousScene,
                nextScene,
                targetObjectKey,
                SIGNED_URL_TTL
        );
    }

    private boolean allScenesHaveClips(List<Map<String, Object>> scenes) {
        return scenes != null && !scenes.isEmpty() && scenes.stream().allMatch(this::hasClipAsset);
    }

    private boolean isResumableVideoRun(Map<String, Object> run, CreatorGenerationJob latestVideoJob) {
        if (run == null || run.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        if (scenes.isEmpty() || allScenesHaveClips(scenes)) {
            return false;
        }
        String latestJobStatus = latestVideoJob == null ? "" : firstText(latestVideoJob.getStatus()).toUpperCase(Locale.ROOT);
        if (latestJobStatus.contains("FAILED") || latestJobStatus.contains("CANCELLED")) {
            return true;
        }
        String status = firstText(run.get("status")).toUpperCase(Locale.ROOT);
        return status.contains("FAILED")
                || status.contains("PARTIAL")
                || status.contains("PAUSED")
                || status.contains("WAITING");
    }

    private Map<String, Object> activeSceneGeneration(List<Map<String, Object>> scenes, String requestedSceneId) {
        if (scenes == null || scenes.isEmpty()) {
            return Map.of();
        }
        String requested = defaultString(requestedSceneId, "");
        for (Map<String, Object> scene : scenes) {
            String sceneId = firstText(scene.get("id"), scene.get("sceneId"), scene.get("scene_id"), "");
            if (!requested.isBlank() && requested.equals(sceneId)) {
                continue;
            }
            if (isActiveSceneGenerationStatus(firstText(scene.get("status")))) {
                return scene;
            }
        }
        return Map.of();
    }

    private boolean isActiveSceneGenerationStatus(String status) {
        String value = defaultString(status, "").toUpperCase(Locale.ROOT);
        return value.equals("GENERATING_VIDEO")
                || value.equals("SCENE_GENERATION_QUEUED")
                || value.equals("VIDEO_GENERATION_QUEUED")
                || value.equals("PROVIDER_QUEUED")
                || value.equals("RUNNING")
                || value.equals("PENDING");
    }

    private boolean hasClipAsset(Map<String, Object> scene) {
        return scene != null
                && !firstText(scene.get("bucket")).isBlank()
                && !firstText(scene.get("objectKey")).isBlank();
    }

    private int sceneProgress(int completedScenes, int totalScenes) {
        int safeTotal = Math.max(1, totalScenes);
        int safeCompleted = Math.max(0, Math.min(completedScenes, safeTotal));
        return Math.max(10, Math.min(92, 10 + (safeCompleted * 82 / safeTotal)));
    }

    private FinalVideoRenderer finalVideoRenderer() {
        return new FinalVideoRenderer(this, assetStorageService);
    }

    private Map<String, Object> compactGeneratedScene(Map<String, Object> scene) {
        Map<String, Object> compact = compactScene(scene);
        compact.put("status", scene.get("status"));
        compact.put("videoUrl", scene.get("videoUrl"));
        compact.put("bucket", scene.get("bucket"));
        compact.put("objectKey", scene.get("objectKey"));
        compact.put("provider", scene.get("provider"));
        compact.put("model", scene.get("model"));
        compact.put("providerOperationId", scene.get("providerOperationId"));
        compact.put("costMetadata", scene.get("costMetadata"));
        return compact;
    }


    Map<String, Object> generatedMusicAsset(Map<String, Object> run) {
        Map<String, Object> backgroundMusic = firstMap(run == null ? null : run.get("backgroundMusic"));
        Map<String, Object> direct = firstNonEmptyMap(
                backgroundMusic.get("asset"),
                firstMap(firstMap(run == null ? null : run.get("audioPack")).get("backgroundMusic")).get("asset"),
                firstMap(firstMap(run == null ? null : run.get("audioProductionPlan")).get("backgroundMusic")).get("asset")
        );
        if (hasStoredAssetLocation(direct)) {
            return direct;
        }
        for (Map<String, Object> asset : mapListValue(run == null ? null : run.get("audioAssets"))) {
            String layerType = firstText(asset.get("layerType"), asset.get("assetKind"), asset.get("assetType")).toLowerCase(Locale.ROOT);
            if (layerType.contains("music") && hasStoredAssetLocation(asset)) {
                return asset;
            }
        }
        return new LinkedHashMap<>();
    }

    boolean hasStoredAssetLocation(Map<String, Object> asset) {
        return !firstText(asset == null ? null : asset.get("bucket")).isBlank()
                && !firstText(asset == null ? null : asset.get("objectKey")).isBlank();
    }

    void runFfmpeg(List<String> command, Path logPath, String failureMessage) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
                    .start();
            boolean finished = process.waitFor(Math.max(60, envInt("SCREENPLAY_VIDEO_FFMPEG_TIMEOUT_SECONDS", 1800)), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage + ": timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage + ": " + tail(readQuietly(logPath), 3000));
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg is not available for screenplay video rendering.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Screenplay video rendering was interrupted.", ex);
        }
    }

    List<String> sceneDialogueAudioConcatCommand(List<Path> audioPaths, Path outputPath) {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-y"));
        for (Path audioPath : audioPaths) {
            command.add("-i");
            command.add(audioPath.toString());
        }

        StringBuilder filter = new StringBuilder();
        for (int index = 0; index < audioPaths.size(); index++) {
            filter.append("[%d:a:0]aresample=48000:async=1:first_pts=0,".formatted(index))
                    .append("aformat=sample_rates=48000:sample_fmts=fltp:channel_layouts=stereo,")
                    .append("asetpts=PTS-STARTPTS")
                    .append(audioPaths.size() == 1 ? "[outa]" : "[a" + index + "]")
                    .append(";");
        }
        if (audioPaths.size() > 1) {
            for (int index = 0; index < audioPaths.size(); index++) {
                filter.append("[a").append(index).append("]");
            }
            filter.append("concat=n=").append(audioPaths.size()).append(":v=0:a=1[outa];");
        }
        if (!filter.isEmpty()) {
            filter.setLength(filter.length() - 1);
        }

        command.addAll(List.of(
                "-filter_complex",
                filter.toString(),
                "-map",
                "[outa]",
                "-vn",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                "-movflags",
                "+faststart",
                outputPath.toString()
        ));
        return command;
    }

    String readQuietly(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException ex) {
            return "";
        }
    }

    String tail(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxChars);
    }

    void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort temp cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort temp cleanup.
        }
    }

    private String sceneClipObjectKey(CreatorScript script, UUID runId, Map<String, Object> scene) {
        return "screenplay-videos/%s/%s/scenes/%03d-%s-%s.mp4".formatted(
                script.getId(),
                runId,
                intValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), 1),
                safeSlug(firstText(scene.get("id"), scene.get("sceneId"), "scene")),
                UUID.randomUUID()
        );
    }

    private String finalVideoObjectKey(CreatorScript script, UUID runId) {
        return "screenplay-videos/%s/%s/final/final-%s.mp4".formatted(script.getId(), runId, UUID.randomUUID());
    }


    Map<String, Object> storeAudioAsset(
            CreatorScript script,
            UUID runId,
            byte[] bytes,
            String contentType,
            String layerType,
            Map<String, Object> metadata,
            Map<String, Object> providerRequest,
            Map<String, Object> providerResponse
    ) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        if (safeBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Audio provider returned an empty file.");
        }
        String safeContentType = firstText(contentType, "audio/mpeg");
        AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAsset(
                audioAssetObjectKey(script, runId, layerType, safeContentType),
                safeBytes,
                safeContentType,
                SIGNED_URL_TTL
        );
        Map<String, Object> providerDetails = compactAudioProviderDetails(metadata);
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        safeMetadata.put("scriptId", script.getId().toString());
        safeMetadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        safeMetadata.put("runId", runId.toString());
        safeMetadata.put("layerType", firstText(layerType, "audio"));
        safeMetadata.put("storageProvider", "minio");
        safeMetadata.put("storageStatus", "SAVED_TO_MINIO");
        if (!providerDetails.isEmpty()) {
            safeMetadata.put("providerDetails", providerDetails);
        }
        String dialogueFingerprint = firstText(metadata == null ? null : metadata.get("dialogueFingerprint"));
        if (!dialogueFingerprint.isBlank()) {
            safeMetadata.put("dialogueFingerprint", dialogueFingerprint);
        }

        CreatorAsset savedAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(audioAssetType(layerType))
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(safeMetadata)
                .build());

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetId", savedAsset.getId().toString());
        asset.put("assetType", savedAsset.getAssetType());
        asset.put("layerType", firstText(layerType, "audio"));
        asset.put("bucket", stored.bucket());
        asset.put("objectKey", stored.objectKey());
        asset.put("contentType", stored.contentType());
        asset.put("sizeBytes", stored.sizeBytes());
        asset.put("assetUrl", stored.signedUrl());
        asset.put("signedUrl", stored.signedUrl());
        asset.put("publicUrl", stored.signedUrl());
        asset.put("storageProvider", "minio");
        asset.put("storageStatus", "SAVED_TO_MINIO");
        if (!providerDetails.isEmpty()) {
            asset.put("providerDetails", providerDetails);
        }
        if (!dialogueFingerprint.isBlank()) {
            asset.put("dialogueFingerprint", dialogueFingerprint);
        }
        asset.put("signedUrlTtlSeconds", SIGNED_URL_TTL.toSeconds());
        asset.put("generatedAt", OffsetDateTime.now().toString());
        return asset;
    }

    Map<String, Object> storeAudioAssetFromPath(
            CreatorScript script,
            UUID runId,
            Path sourcePath,
            String contentType,
            String layerType,
            Map<String, Object> metadata
    ) throws IOException {
        if (!Files.isRegularFile(sourcePath) || Files.size(sourcePath) <= 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Combined audio output was empty.");
        }
        String safeContentType = firstText(contentType, "audio/mp4");
        AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAssetFromPath(
                audioAssetObjectKey(script, runId, layerType, safeContentType),
                sourcePath,
                safeContentType,
                SIGNED_URL_TTL
        );
        Map<String, Object> providerDetails = compactAudioProviderDetails(metadata);
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        safeMetadata.put("scriptId", script.getId().toString());
        safeMetadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        safeMetadata.put("runId", runId.toString());
        safeMetadata.put("layerType", firstText(layerType, "audio"));
        safeMetadata.put("storageProvider", "minio");
        safeMetadata.put("storageStatus", "SAVED_TO_MINIO");
        if (!providerDetails.isEmpty()) {
            safeMetadata.put("providerDetails", providerDetails);
        }
        String dialogueFingerprint = firstText(metadata == null ? null : metadata.get("dialogueFingerprint"));
        if (!dialogueFingerprint.isBlank()) {
            safeMetadata.put("dialogueFingerprint", dialogueFingerprint);
        }

        CreatorAsset savedAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(audioAssetType(layerType))
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(safeMetadata)
                .build());

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetId", savedAsset.getId().toString());
        asset.put("assetType", savedAsset.getAssetType());
        asset.put("layerType", firstText(layerType, "audio"));
        asset.put("bucket", stored.bucket());
        asset.put("objectKey", stored.objectKey());
        asset.put("contentType", stored.contentType());
        asset.put("sizeBytes", stored.sizeBytes());
        asset.put("assetUrl", stored.signedUrl());
        asset.put("signedUrl", stored.signedUrl());
        asset.put("publicUrl", stored.signedUrl());
        asset.put("storageProvider", "minio");
        asset.put("storageStatus", "SAVED_TO_MINIO");
        if (!providerDetails.isEmpty()) {
            asset.put("providerDetails", providerDetails);
        }
        if (!dialogueFingerprint.isBlank()) {
            asset.put("dialogueFingerprint", dialogueFingerprint);
        }
        asset.put("signedUrlTtlSeconds", SIGNED_URL_TTL.toSeconds());
        asset.put("generatedAt", OffsetDateTime.now().toString());
        return asset;
    }

    private Map<String, Object> compactAudioProviderDetails(Map<String, Object> metadata) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (metadata == null || metadata.isEmpty()) {
            return details;
        }
        putIfPresent(details, "provider", metadata.get("provider"));
        putIfPresent(details, "model", metadata.get("model"));
        putIfPresent(details, "voiceName", metadata.get("voiceName"));
        putIfPresent(details, "languageCode", metadata.get("languageCode"));
        putIfPresent(details, "audioEncoding", metadata.get("audioEncoding"));
        putIfPresent(details, "requestedDurationSeconds", metadata.get("requestedDurationSeconds"));
        putIfPresent(details, "layerType", metadata.get("layerType"));
        putIfPresent(details, "mimeType", metadata.get("mimeType"));
        Map<String, Object> costMetadata = firstMap(metadata.get("costMetadata"));
        if (!costMetadata.isEmpty()) {
            details.put("costMetadata", sanitizeProviderStorageMap(costMetadata));
        }
        return details;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private String audioAssetType(String layerType) {
        String normalized = defaultString(layerType, "audio").toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (normalized.contains("voice")) {
            return ASSET_TYPE_SCREENPLAY_AUDIO_VOICEOVER;
        }
        if (normalized.contains("music")) {
            return ASSET_TYPE_SCREENPLAY_AUDIO_MUSIC_BED;
        }
        return ASSET_TYPE_SCREENPLAY_AUDIO;
    }

    private String audioAssetObjectKey(CreatorScript script, UUID runId, String layerType, String contentType) {
        return "screenplay-videos/%s/%s/audio/%s-%s.%s".formatted(
                script.getId(),
                runId,
                safeSlug(firstText(layerType, "audio")),
                UUID.randomUUID(),
                audioFileExtension(contentType)
        );
    }

    String audioFileExtension(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("wav")) {
            return "wav";
        }
        if (normalized.contains("ogg")) {
            return "ogg";
        }
        if (normalized.contains("aac")) {
            return "aac";
        }
        if (normalized.contains("mp4") || normalized.contains("m4a")) {
            return "m4a";
        }
        return "mp3";
    }

    private boolean isImageContentType(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        return normalized.startsWith("image/")
                && (normalized.contains("jpeg")
                || normalized.contains("jpg")
                || normalized.contains("png")
                || normalized.contains("webp")
                || normalized.contains("avif")
                || normalized.contains("gif"));
    }

    private boolean isVideoContentType(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        return normalized.startsWith("video/")
                || normalized.contains("mp4")
                || normalized.contains("quicktime")
                || normalized.contains("webm")
                || normalized.contains("x-matroska");
    }

    private String videoFileExtension(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) {
            return "mov";
        }
        if (normalized.contains("webm")) {
            return "webm";
        }
        if (normalized.contains("matroska")) {
            return "mkv";
        }
        return "mp4";
    }

    private String videoContentTypeForFilename(String filename) {
        String normalized = defaultString(filename, "").toLowerCase(Locale.ROOT).trim();
        if (normalized.endsWith(".mov")) return "video/quicktime";
        if (normalized.endsWith(".webm")) return "video/webm";
        if (normalized.endsWith(".mkv")) return "video/x-matroska";
        if (normalized.endsWith(".avi")) return "video/x-msvideo";
        if (normalized.endsWith(".mpeg") || normalized.endsWith(".mpg")) return "video/mpeg";
        if (normalized.endsWith(".mp4") || normalized.endsWith(".m4v")) return "video/mp4";
        return "";
    }

    private String imageFileExtension(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("png")) {
            return "png";
        }
        if (normalized.contains("webp")) {
            return "webp";
        }
        if (normalized.contains("avif")) {
            return "avif";
        }
        if (normalized.contains("gif")) {
            return "gif";
        }
        return "jpg";
    }

    private String audioPackVoiceText(Map<String, Object> inputPayload, Map<String, Object> run, CreatorScript script) {
        String requested = firstText(
                inputPayload == null ? null : inputPayload.get("voiceText"),
                inputPayload == null ? null : inputPayload.get("dialogueText"),
                inputPayload == null ? null : inputPayload.get("script"),
                inputPayload == null ? null : inputPayload.get("voiceoverScript")
        );
        if (!requested.isBlank()) {
            return truncate(requested, 4800);
        }
        Map<String, Object> audioProductionPlan = firstMap(
                inputPayload == null ? null : inputPayload.get("audioProductionPlan"),
                run == null ? null : run.get("audioProductionPlan")
        );
        Map<String, Object> dialoguePlan = firstMap(
                audioProductionPlan.get("dialoguePlan"),
                audioProductionPlan.get("voiceDialogue"),
                run == null ? null : run.get("dialoguePlan")
        );
        String planned = firstText(
                dialoguePlan.get("voiceoverScript"),
                dialoguePlan.get("dialogueScript"),
                dialoguePlan.get("script"),
                dialoguePlan.get("prompt"),
                run == null ? null : run.get("voiceoverScript"),
                run == null ? null : run.get("dialogueScript")
        );
        if (!planned.isBlank() && !planned.equalsIgnoreCase("auto")) {
            return truncate(planned, 4800);
        }
        String cueText = srtCueText(firstList(
                run == null ? null : run.get("srtCues"),
                firstMap(run == null ? null : run.get("srtFile")).get("cues"),
                firstMap(inputPayload == null ? null : inputPayload.get("srtFile")).get("cues")
        ));
        if (!cueText.isBlank()) {
            return truncate(cueText, 4800);
        }
        StringBuilder scenesText = new StringBuilder();
        for (Map<String, Object> scene : mapListValue(run == null ? null : run.get("scenes"))) {
            String line = firstText(
                    scene.get("voiceover"),
                    scene.get("dialogue"),
                    scene.get("spokenLine"),
                    scene.get("caption"),
                    scene.get("textOverlay")
            );
            if (!line.isBlank()) {
                if (!scenesText.isEmpty()) {
                    scenesText.append(System.lineSeparator());
                }
                scenesText.append(line);
            }
        }
        if (!scenesText.isEmpty()) {
            return truncate(scenesText.toString(), 4800);
        }
        return truncate(defaultString(script == null ? null : script.getScriptText(), ""), 4800);
    }

    private String srtCueText(List<Object> cues) {
        StringBuilder builder = new StringBuilder();
        for (Object cue : cues == null ? List.of() : cues) {
            Map<String, Object> cueMap = mapValue(cue);
            String line = firstText(cueMap.get("text"), cueMap.get("caption"), cueMap.get("dialogue"), cueMap.get("line"));
            if (!line.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private String audioPackMusicPrompt(
            Map<String, Object> inputPayload,
            Map<String, Object> audioProductionPlan,
            Map<String, Object> soundDesignPlan,
            Map<String, Object> run
    ) {
        Map<String, Object> musicPlan = firstMap(
                inputPayload == null ? null : inputPayload.get("musicPlan"),
                audioProductionPlan == null ? null : audioProductionPlan.get("musicPlan"),
                audioProductionPlan == null ? null : audioProductionPlan.get("music"),
                soundDesignPlan == null ? null : soundDesignPlan.get("backgroundMusic"),
                run == null ? null : run.get("musicPlan")
        );
        Map<String, Object> freeMusicPlan = firstMap(
                inputPayload == null ? null : inputPayload.get("freeMusicPlan"),
                audioProductionPlan == null ? null : audioProductionPlan.get("freeMusicPlan"),
                soundDesignPlan == null ? null : soundDesignPlan.get("freeMusicPlan"),
                run == null ? null : run.get("freeMusicPlan")
        );
        String prompt = firstText(
                inputPayload == null ? null : inputPayload.get("musicPrompt"),
                inputPayload == null ? null : inputPayload.get("backgroundMusicPrompt"),
                musicPlan.get("prompt"),
                musicPlan.get("brief"),
                freeMusicPlan.get("searchQuery"),
                freeMusicPlan.get("fallbackQuery"),
                run == null ? null : run.get("backgroundMusicPrompt")
        );
        if (!prompt.isBlank() && !prompt.equalsIgnoreCase("auto")) {
            return backgroundMusicGenerationPrompt(prompt, inputPayload, audioProductionPlan, soundDesignPlan, run);
        }
        Map<String, Object> pacingProfile = firstMap(run == null ? null : run.get("videoPacingProfile"));
        String pace = firstText(pacingProfile.get("paceKey"), pacingProfile.get("pace"), pacingProfile.get("style"), "medium paced");
        String title = firstText(run == null ? null : run.get("title"), "product commercial");
        return backgroundMusicGenerationPrompt(
                "Royalty-safe instrumental background music for a %s short-form ad. Pace: %s.".formatted(title, pace),
                inputPayload,
                audioProductionPlan,
                soundDesignPlan,
                run
        );
    }

    private void applyAudioRequestType(Map<String, Object> inputPayload) {
        if (inputPayload == null) {
            return;
        }
        String requestType = normalizeAudioRequestType(firstText(
                inputPayload.get("audioRequestType"),
                inputPayload.get("audio_request_type"),
                inputPayload.get("requestType"),
                inputPayload.get("request_type")
        ));
        if (requestType.isBlank()) {
            return;
        }
        inputPayload.put("audioRequestType", requestType);
        if ("dialogue".equals(requestType)) {
            inputPayload.put("generateDialogue", true);
            inputPayload.put("generateVoice", true);
            inputPayload.put("musicSource", "none");
            inputPayload.put("backgroundMusicSource", "none");
            inputPayload.put("generateMusic", false);
            inputPayload.put("generateAiMusic", false);
            return;
        }
        if ("background_music".equals(requestType)) {
            inputPayload.put("generateDialogue", false);
            inputPayload.put("generateVoice", false);
            String musicSource = normalizeMusicSource(firstText(
                    inputPayload.get("musicSource"),
                    inputPayload.get("backgroundMusicSource"),
                    "free_licensed"
            ));
            inputPayload.put("musicSource", musicSource);
            inputPayload.put("backgroundMusicSource", musicSource);
            boolean aiMusic = "ai_generated".equals(musicSource);
            inputPayload.put("generateMusic", aiMusic);
            inputPayload.put("generateAiMusic", aiMusic);
        }
    }

    private String normalizeAudioRequestType(String value) {
        String normalized = defaultString(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.equals("dialogue") || normalized.equals("voice") || normalized.equals("voiceover")
                || normalized.equals("dialogue_voice") || normalized.equals("dialogue_voiceover")) {
            return "dialogue";
        }
        if (normalized.equals("music") || normalized.equals("background_music") || normalized.equals("bgm")
                || normalized.equals("music_bed") || normalized.equals("background_music_bed")) {
            return "background_music";
        }
        return "";
    }

    private String backgroundMusicGenerationPrompt(
            String creativeDirection,
            Map<String, Object> inputPayload,
            Map<String, Object> audioProductionPlan,
            Map<String, Object> soundDesignPlan,
            Map<String, Object> run
    ) {
        Map<String, Object> pacingProfile = firstMap(run == null ? null : run.get("videoPacingProfile"));
        Map<String, Object> finishingPlan = firstMap(inputPayload == null ? null : inputPayload.get("videoFinishingPlan"), run == null ? null : run.get("videoFinishingPlan"));
        Map<String, Object> musicPlan = firstMap(
                audioProductionPlan == null ? null : audioProductionPlan.get("music"),
                soundDesignPlan == null ? null : soundDesignPlan.get("backgroundMusic"),
                inputPayload == null ? null : inputPayload.get("musicPlan")
        );
        String title = firstText(
                run == null ? null : run.get("title"),
                firstMap(run == null ? null : run.get("screenplayJson")).get("projectTitle"),
                "short-form commercial"
        );
        String category = firstText(
                run == null ? null : run.get("category"),
                run == null ? null : run.get("topicType"),
                firstMap(run == null ? null : run.get("productUnderstanding")).get("productCategory"),
                "advertisement"
        );
        String pace = firstText(
                pacingProfile.get("paceKey"),
                pacingProfile.get("pace"),
                pacingProfile.get("style"),
                finishingPlan.get("pacing"),
                "medium paced"
        );
        String tempo = firstText(
                musicPlan.get("tempo"),
                pacingProfile.get("musicTempo"),
                pacingProfile.get("tempo"),
                tempoForPace(pace)
        );
        String mood = firstText(
                musicPlan.get("mood"),
                finishingPlan.get("mood"),
                pacingProfile.get("mood"),
                soundDesignPlan == null ? null : soundDesignPlan.get("mood"),
                "confident, cinematic, modern"
        );
        int durationSeconds = positiveInt(firstValue(
                inputPayload == null ? null : inputPayload.get("durationSeconds"),
                inputPayload == null ? null : inputPayload.get("targetDurationSeconds"),
                run == null ? null : run.get("durationSeconds")
        ), 60);
        String structure = backgroundMusicSceneStructure(run);
        String characterContext = backgroundMusicCharacterContext(run);
        String ambiencePrompt = firstText(
                firstMap(soundDesignPlan == null ? null : soundDesignPlan.get("ambience")).get("prompt"),
                audioProductionPlan == null ? null : firstMap(audioProductionPlan.get("ambience")).get("prompt"),
                finishingPlan.get("ambiencePrompt"),
                "subtle room tone that matches each scene"
        );
        String sfxPrompt = firstText(
                firstMap(soundDesignPlan == null ? null : soundDesignPlan.get("soundEffects")).get("prompt"),
                audioProductionPlan == null ? null : firstMap(audioProductionPlan.get("soundEffects")).get("prompt"),
                finishingPlan.get("soundFxPrompt"),
                "small whooshes, clicks, and transition accents only where useful"
        );
        return truncate("""
                Create original instrumental background music for this finished video.

                Video: %s
                Category: %s
                Creative direction: %s
                Mood: %s
                Tempo: %s
                Target length: %d seconds

                Cast context for emotional scoring: %s
                Use the stated character gender, role, and age only to shape instrumentation, energy, and emotional framing. Keep the output instrumental and do not infer or generate a voice from this context.

                Timeline structure:
                %s

                Mix and sound design requirements:
                - No vocals, no lyrics, no spoken dialogue, no narration.
                - Dialogue is primary. Leave the vocal midrange clear and keep music ready to duck under speech.
                - Keep the bed loopable and easy to trim for a 60 second vertical ad.
                - Keep ambience subtle: %s.
                - Use sound effects sparingly: %s.
                - Match reverb to the scene spaces and camera distance.
                - Build smooth fades between scenes and avoid abrupt endings.
                - Make the melody original and royalty-safe; do not imitate famous songs, artists, jingles, or copyrighted melodies.
                """.formatted(
                title,
                category,
                firstText(creativeDirection, "modern commercial background score"),
                mood,
                tempo,
                Math.max(4, Math.min(90, durationSeconds)),
                characterContext,
                structure,
                ambiencePrompt,
                sfxPrompt
        ).trim(), 2200);
    }

    private String tempoForPace(String pace) {
        String normalized = defaultString(pace, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("fast") || normalized.contains("rapid") || normalized.contains("high")) {
            return "120-145 BPM, energetic but not busy";
        }
        if (normalized.contains("slow") || normalized.contains("luxury") || normalized.contains("calm")) {
            return "70-95 BPM, spacious and premium";
        }
        return "95-120 BPM, steady commercial pulse";
    }

    private String backgroundMusicSceneStructure(Map<String, Object> run) {
        List<Map<String, Object>> scenes = mapListValue(run == null ? null : run.get("scenes"));
        if (scenes.isEmpty()) {
            return "- Start with a clear hook, build through the proof section, and resolve cleanly under the CTA.";
        }
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(8, scenes.size());
        for (int index = 0; index < limit; index++) {
            Map<String, Object> scene = scenes.get(index);
            String time = firstText(scene.get("timeline"), scene.get("timeRange"), scene.get("time_range"), scene.get("time"), "Scene " + (index + 1));
            String title = firstText(scene.get("title"), scene.get("hook"), scene.get("name"), "Scene " + (index + 1));
            String action = firstText(scene.get("action"), scene.get("visual"), scene.get("description"), scene.get("camera"), scene.get("shotDescription"));
            builder.append("- ")
                    .append(time)
                    .append(": ")
                    .append(title);
            if (!action.isBlank()) {
                builder.append(" - ").append(truncate(normalizeWhitespace(action), 160));
            }
            builder.append(System.lineSeparator());
        }
        if (scenes.size() > limit) {
            builder.append("- Continue the same motif through remaining scenes, lifting gently into the CTA.");
        }
        return builder.toString().trim();
    }

    private String backgroundMusicCharacterContext(Map<String, Object> run) {
        Map<String, Object> screenplay = firstMap(
                run == null ? null : run.get("screenplayJson"),
                run == null ? null : run.get("scriptJson")
        );
        List<Map<String, Object>> characters = mapListValue(firstValue(
                run == null ? null : run.get("storyCharacters"),
                run == null ? null : run.get("characters"),
                screenplay.get("storyCharacters"),
                screenplay.get("characters")
        ));
        if (characters.isEmpty()) {
            return "No cast gender was supplied. Keep the score inclusive, neutral, and led by the story beat.";
        }

        List<String> descriptions = new ArrayList<>();
        for (Map<String, Object> character : characters) {
            String name = firstText(character.get("name"), character.get("characterName"), character.get("id"), "Character");
            String gender = firstText(character.get("gender"), character.get("genderIdentity"), "unspecified");
            String role = firstText(character.get("characterRole"), character.get("role"), character.get("roleInShort"), "on-screen lead");
            String age = firstText(character.get("age"), character.get("ageRange"), character.get("ageGroup"));
            descriptions.add("%s (%s, %s%s)".formatted(
                    name,
                    gender,
                    role,
                    age.isBlank() ? "" : ", " + age
            ));
        }
        return truncate(String.join("; ", descriptions), 600);
    }

    private String normalizeMusicSource(String value) {
        String normalized = defaultString(value, "free_licensed")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank() || "auto".equals(normalized) || "royalty_free".equals(normalized)
                || "copyright_free".equals(normalized) || "non_copyright".equals(normalized)
                || "non_copyrighted".equals(normalized) || "free".equals(normalized)
                || "free_music".equals(normalized) || "free_licensed_music".equals(normalized)) {
            return "free_licensed";
        }
        if ("ai".equals(normalized) || "ai_music".equals(normalized) || "generated".equals(normalized)
                || "ai_generated".equals(normalized) || "google_lyria".equals(normalized) || "lyria".equals(normalized)) {
            return "ai_generated";
        }
        if ("none".equals(normalized) || "no_music".equals(normalized) || "off".equals(normalized)) {
            return "none";
        }
        return "free_licensed";
    }

    private String voiceProviderFrom(Object value) {
        String normalized = stringValue(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        if (normalized.equals("dalai_llama")
                || normalized.equals("dallai_llama")
                || normalized.equals("local")
                || normalized.equals("open_source")) {
            return "dalai_llama";
        }
        if (normalized.equals("elevenlabs") || normalized.equals("eleven_labs")) {
            return "elevenlabs";
        }
        if (normalized.equals("google")
                || normalized.equals("google_chirp")
                || normalized.equals("chirp")
                || normalized.equals("gemini_tts")
                || normalized.equals("gemini")) {
            return "google_chirp";
        }
        return "";
    }

    Map<String, Object> dialogueVoiceProfile(
            Map<String, Object> inputPayload,
            Map<String, Object> run,
            Map<String, Object> scriptPayload
    ) {
        Map<String, Object> requestedProfile = firstMap(
                inputPayload == null ? null : inputPayload.get("dialogueVoiceProfile"),
                inputPayload == null ? null : inputPayload.get("voiceProfile"),
                run == null ? null : run.get("dialogueVoiceProfile"),
                scriptPayload == null ? null : scriptPayload.get("dialogueVoiceProfile")
        );
        String requestedGender = normalizeVoiceGender(firstText(
                inputPayload == null ? null : inputPayload.get("voiceGender"),
                inputPayload == null ? null : inputPayload.get("dialogueVoiceGender"),
                requestedProfile.get("voiceGender"),
                requestedProfile.get("gender")
        ));
        String requestedSpeaker = firstText(
                inputPayload == null ? null : inputPayload.get("speakerName"),
                inputPayload == null ? null : inputPayload.get("dialogueSpeakerName"),
                requestedProfile.get("speakerName"),
                requestedProfile.get("name")
        );

        List<Map<String, Object>> candidates = new ArrayList<>();
        addVoiceCandidates(candidates, inputPayload == null ? null : inputPayload.get("characterCastMappings"));
        addVoiceCandidates(candidates, inputPayload == null ? null : inputPayload.get("castMappings"));
        addVoiceCandidates(candidates, run == null ? null : run.get("characterCastMappings"));
        addVoiceCandidates(candidates, scriptPayload == null ? null : scriptPayload.get("characterCastMappings"));
        addVoiceCandidates(candidates, firstMap(scriptPayload == null ? null : scriptPayload.get("creatorContext")).get("characterCastMappings"));
        addVoiceCandidates(candidates, inputPayload == null ? null : inputPayload.get("availableActors"));
        addVoiceCandidates(candidates, run == null ? null : run.get("availableActors"));
        addVoiceCandidates(candidates, scriptPayload == null ? null : scriptPayload.get("availableActors"));
        addVoiceCandidates(candidates, run == null ? null : run.get("storyCharacters"));
        addVoiceCandidates(candidates, scriptPayload == null ? null : scriptPayload.get("storyCharacters"));
        addVoiceCandidates(candidates, scriptPayload == null ? null : scriptPayload.get("characters"));

        Map<String, Object> lead = candidates.stream()
                .max((left, right) -> Integer.compare(voiceCandidateScore(left), voiceCandidateScore(right)))
                .orElseGet(LinkedHashMap::new);
        Map<String, Object> cast = firstMap(lead.get("castPayload"), lead.get("actor"), lead.get("cast"), lead);
        Map<String, Object> character = firstMap(lead.get("characterPayload"), lead.get("character"), lead);
        String gender = firstText(requestedGender, normalizeVoiceGender(firstText(
                cast.get("gender"),
                cast.get("genderIdentity"),
                character.get("gender"),
                character.get("genderIdentity"),
                lead.get("gender"),
                lead.get("genderIdentity")
        )));
        String speakerName = firstText(
                requestedSpeaker,
                cast.get("name"),
                cast.get("displayName"),
                character.get("name"),
                character.get("characterName"),
                lead.get("castDisplayName"),
                lead.get("characterName")
        );
        Map<String, Object> profile = new LinkedHashMap<>();
        if (!gender.isBlank()) {
            profile.put("voiceGender", gender);
        }
        if (!speakerName.isBlank()) {
            profile.put("speakerName", speakerName);
        }
        profile.put("selectionSource", requestedGender.isBlank() ? (lead.isEmpty() ? "configured_default" : "cast_mapping") : "user_selected_cast");
        return profile;
    }

    private void addVoiceCandidates(List<Map<String, Object>> candidates, Object value) {
        if (candidates != null) {
            candidates.addAll(mapListValue(value));
        }
    }

    private int voiceCandidateScore(Map<String, Object> candidate) {
        Map<String, Object> cast = firstMap(candidate == null ? null : candidate.get("castPayload"), candidate == null ? null : candidate.get("actor"), candidate == null ? null : candidate.get("cast"), candidate);
        String role = firstText(
                candidate == null ? null : candidate.get("characterRole"),
                candidate == null ? null : candidate.get("roleInShort"),
                candidate == null ? null : candidate.get("role"),
                cast.get("roleInShort"),
                cast.get("role")
        ).toLowerCase(Locale.ROOT);
        int score = 0;
        if (role.contains("main")) score += 30;
        if (role.contains("lead") || role.contains("primary") || role.contains("narrator")) score += 20;
        if (!normalizeVoiceGender(firstText(cast.get("gender"), candidate == null ? null : candidate.get("gender"))).isBlank()) score += 10;
        if (!firstText(cast.get("name"), candidate == null ? null : candidate.get("characterName")).isBlank()) score += 1;
        return score;
    }

    private String normalizeVoiceGender(String value) {
        String normalized = defaultString(value, "").toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
        if (normalized.startsWith("female") || "woman".equals(normalized) || "girl".equals(normalized)) {
            return "female";
        }
        if (normalized.startsWith("male") || "man".equals(normalized) || "boy".equals(normalized)) {
            return "male";
        }
        return "";
    }

    private String dialogueFingerprint(String voiceText, Map<String, Object> inputPayload, Map<String, Object> run) {
        String material = String.join("|",
                normalizeWhitespace(voiceText),
                firstText(inputPayload == null ? null : inputPayload.get("voiceProvider"), inputPayload == null ? null : inputPayload.get("provider"), "google_chirp"),
                firstText(inputPayload == null ? null : inputPayload.get("voiceName"), inputPayload == null ? null : inputPayload.get("voice"), ""),
                firstText(inputPayload == null ? null : inputPayload.get("voiceGender"), inputPayload == null ? null : inputPayload.get("dialogueVoiceGender"), run == null ? null : firstMap(run.get("dialogueVoiceProfile")).get("voiceGender"), ""),
                firstText(inputPayload == null ? null : inputPayload.get("languageCode"), run == null ? null : run.get("languageCode"), "")
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return Integer.toHexString(material.hashCode());
        }
    }

    String stableFingerprint(String... parts) {
        String material = String.join("|", parts == null ? new String[0] : parts);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return Integer.toHexString(material.hashCode());
        }
    }

    private Map<String, Object> founderLocalModels(
            String localVoiceModel,
            String localTalkingAvatarModel,
            String localLipSyncModel,
            String localImageModel,
            String localVideoModel
    ) {
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("voiceModel", normalizeLocalVoiceModel(localVoiceModel));
        models.put("talkingAvatarModel", normalizeLocalTalkingAvatarModel(localTalkingAvatarModel));
        models.put("lipSyncModel", normalizeLocalLipSyncModel(localLipSyncModel));
        models.put("imageModel", normalizeLocalImageModel(localImageModel));
        models.put("lightingModel", "ic_lightning");
        models.put("videoModel", normalizeLocalVideoModel(localVideoModel));
        models.put("gpuProfile", "rtx_4060_8gb");
        models.put("language", "hinglish");
        models.put("languageCode", "hi-IN");
        return models;
    }

    private Map<String, Object> founderEmbeddingIds(String fingerprint) {
        String safe = firstText(fingerprint, stableFingerprint(UUID.randomUUID().toString()));
        String suffix = safe.substring(0, Math.min(16, safe.length()));
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("portraitEmbeddingId", "portrait-" + suffix);
        ids.put("facialFeatureEmbeddingId", "face-" + suffix);
        ids.put("voiceEmbeddingId", "voice-" + suffix);
        ids.put("identityEmbeddingVersion", "founder-kit-v1");
        return ids;
    }

    private String normalizeLocalVoiceModel(String value) {
        return localAvatarModelNormalizer().normalizeLocalVoiceModel(value);
    }

    String requireSceneVoiceMethod(String value) {
        return localAvatarModelNormalizer().requireSceneVoiceMethod(value);
    }

    String providerVoiceIdForMethod(String voiceModel, Map<String, Object> founderProfile) {
        return localAvatarModelNormalizer().providerVoiceIdForMethod(voiceModel, founderProfile);
    }

    String applyPronunciationGuide(String text, String guide) {
        return localAvatarModelNormalizer().applyPronunciationGuide(text, guide);
    }

    String normalizeLocalTalkingAvatarModel(String value) {
        return localAvatarModelNormalizer().normalizeLocalTalkingAvatarModel(value);
    }

    String normalizeLocalLipSyncModel(String value) {
        return localAvatarModelNormalizer().normalizeLocalLipSyncModel(value);
    }

    private String normalizeLocalImageModel(String value) {
        return localAvatarModelNormalizer().normalizeLocalImageModel(value);
    }

    private String normalizeLocalVideoModel(String value) {
        return localAvatarModelNormalizer().normalizeLocalVideoModel(value);
    }

    private LocalAvatarModelNormalizer localAvatarModelNormalizer() {
        return new LocalAvatarModelNormalizer();
    }

    boolean sameLanguage(String left, String right) {
        return dialogueLanguageCatalog().sameLanguage(left, right);
    }

    String languageCodeFor(String language) {
        return dialogueLanguageCatalog().languageCodeFor(language);
    }

    private DialogueLanguageCatalog dialogueLanguageCatalog() {
        return new DialogueLanguageCatalog();
    }

    private Map<String, Object> freeMusicSelectionPlan(
            Map<String, Object> inputPayload,
            Map<String, Object> audioProductionPlan,
            Map<String, Object> soundDesignPlan,
            Map<String, Object> run,
            String musicPrompt
    ) {
        Map<String, Object> existing = new LinkedHashMap<>(firstMap(
                inputPayload == null ? null : inputPayload.get("freeMusicPlan"),
                inputPayload == null ? null : inputPayload.get("freeMusicSelectionPlan"),
                audioProductionPlan == null ? null : audioProductionPlan.get("freeMusicSelectionPlan"),
                audioProductionPlan == null ? null : audioProductionPlan.get("freeMusicPlan"),
                soundDesignPlan == null ? null : soundDesignPlan.get("freeMusicPlan"),
                run == null ? null : run.get("freeMusicSelectionPlan"),
                run == null ? null : run.get("freeMusicPlan")
        ));
        String searchQuery = firstText(
                existing.get("searchQuery"),
                existing.get("query"),
                existing.get("fallbackQuery"),
                musicPrompt,
                "modern instrumental ad music"
        );
        Map<String, Object> plan = new LinkedHashMap<>(existing);
        plan.put("status", "NEEDS_USER_SELECTION");
        plan.put("sourceType", "free_licensed");
        plan.put("description", firstText(existing.get("description"), existing.get("brief"), musicPrompt));
        plan.put("searchQuery", truncate(searchQuery, 240));
        plan.put("prompt", truncate(firstText(existing.get("prompt"), musicPrompt, searchQuery), 1600));
        plan.put("licensePolicy", firstText(
                existing.get("licensePolicy"),
                "Use only music with explicit commercial-use permission. Save the source URL, license URL, attribution text, and download timestamp before the final edit."
        ));
        plan.put("editorInstruction", firstText(
                existing.get("editorInstruction"),
                "Choose a free-licensed instrumental track that matches the brief, duck it under dialogue, and include license proof in delivery notes."
        ));
        if (firstList(plan.get("candidateSources")).isEmpty()) {
            plan.put("candidateSources", freeMusicCandidateSources(searchQuery));
        }
        plan.putIfAbsent("selectionFieldsRequired", List.of(
                "trackTitle",
                "artist",
                "sourceUrl",
                "licenseUrl",
                "attributionRequired",
                "commercialUseAllowed",
                "downloadedAt"
        ));
        plan.putIfAbsent("mixRequirements", List.of(
                "Dialogue remains the loudest element.",
                "Music is ducked under speech.",
                "Use smooth fades at segment boundaries.",
                "Avoid recognizable copyrighted melodies or artist imitation."
        ));
        plan.put("generatedAt", OffsetDateTime.now().toString());
        return plan;
    }

    private List<Map<String, Object>> freeMusicCandidateSources(String searchQuery) {
        String encoded = urlEncode(searchQuery);
        return List.of(
                Map.of(
                        "source", "Pixabay Music",
                        "searchUrl", "https://pixabay.com/music/search/" + encoded + "/",
                        "licenseNote", "Verify the current Pixabay license and attribution rules before using the track commercially."
                ),
                Map.of(
                        "source", "Free Music Archive",
                        "searchUrl", "https://freemusicarchive.org/search/?quicksearch=" + encoded,
                        "licenseNote", "Use only tracks whose license allows the intended commercial use; record attribution requirements."
                ),
                Map.of(
                        "source", "Mixkit",
                        "searchUrl", "https://mixkit.co/free-stock-music/",
                        "licenseNote", "Verify the current Mixkit license and keep the track page URL in the editor handoff."
                ),
                Map.of(
                        "source", "YouTube Audio Library",
                        "searchUrl", "https://studio.youtube.com/channel/UC/music",
                        "licenseNote", "Use only tracks whose license permits the target usage; capture attribution text when required."
                )
        );
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(defaultString(value, ""), StandardCharsets.UTF_8);
    }

    private String audioCompletionMessage(
            Map<String, Object> dialogue,
            Map<String, Object> backgroundMusic,
            List<Map<String, Object>> assets
    ) {
        String dialogueStatus = firstText(dialogue == null ? null : dialogue.get("status"));
        String musicSource = firstText(backgroundMusic == null ? null : backgroundMusic.get("sourceType"));
        String musicStatus = firstText(backgroundMusic == null ? null : backgroundMusic.get("status"));
        if ("free_licensed".equals(musicSource) && (dialogueStatus.equals("GENERATED") || dialogueStatus.equals("REUSED"))) {
            return "Dialogue voiceover is ready and the free music selection plan is prepared.";
        }
        if ("free_licensed".equals(musicSource)) {
            return "Free music selection plan is prepared.";
        }
        if ("ai_generated".equals(musicSource) && "GENERATED".equals(musicStatus)) {
            return "Dialogue and AI-generated background music are ready.";
        }
        if (assets != null && !assets.isEmpty()) {
            return "Dialogue and background music assets are attached to the video run.";
        }
        return "Dialogue and background music plan is ready.";
    }

    String safeSlug(String value) {
        String normalized = defaultString(value, "scene")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "scene" : normalized;
    }

    private String providerLabel(String provider) {
        if ("google_veo".equals(provider)) {
            return "Google Veo";
        }
        if ("gemini_omni".equals(provider)) {
            return "Gemini Omni Flash";
        }
        if ("synthesia".equals(provider)) {
            return "Synthesia";
        }
        if ("dalai_llama".equals(provider)) {
            return "Dalai Llama local";
        }
        return "omini".equals(provider) ? "Omini" : "Seedance";
    }

    private Map<String, Object> buildInitialRun(
            CreatorScript script,
            Map<String, Object> request,
            UUID runId,
            UUID jobId,
            String provider,
            String model,
            int maxClipSeconds
    ) {
        return new InitialRunAssembler(this, shotPlanRepository)
                .buildInitialRun(script, request, runId, jobId, provider, model, maxClipSeconds);
    }

    private Map<String, Object> localizeAiSceneForGeneration(
            CreatorScript script,
            Map<String, Object> sourceScene,
            Map<String, Object> run,
            Map<String, Object> request,
            String generationMode,
            UUID jobId
    ) {
        Map<String, Object> scene = copyMap(sourceScene);
        if ("talking_head".equals(generationMode)) {
            return scene;
        }

        String targetLanguage = firstText(
                request == null ? null : request.get("dialogueLanguage"),
                request == null ? null : request.get("language"),
                scene.get("dialogueLanguage"),
                run == null ? null : run.get("dialogueLanguage")
        );
        if (targetLanguage.isBlank()) {
            return scene;
        }
        String sourceLanguage = firstText(
                scene.get("sourceDialogueLanguage"),
                request == null ? null : request.get("sourceDialogueLanguage"),
                run == null ? null : run.get("sourceDialogueLanguage"),
                script == null ? null : script.getDialogueLanguage(),
                script == null ? null : firstMap(script.getScriptPayload()).get("dialogueLanguage"),
                "Hinglish"
        );
        String currentLanguage = firstText(
                scene.get("dialogueLanguage"),
                run == null ? null : run.get("dialogueLanguage"),
                sourceLanguage
        );
        String canonicalDialogue = firstText(
                scene.get("sourceDialogueScript"),
                scene.get("source_dialogue_script"),
                dialogueTextForScene(scene)
        );
        String targetLanguageCode = firstText(
                request == null ? null : request.get("languageCode"),
                languageCodeFor(targetLanguage)
        );

        if (canonicalDialogue.isBlank()) {
            scene.put("sourceDialogueLanguage", sourceLanguage);
            scene.put("dialogueLanguage", targetLanguage);
            scene.put("languageCode", targetLanguageCode);
            scene.put("dialogueLocalizationStatus", "NO_DIALOGUE");
            return scene;
        }
        if (sameLanguage(currentLanguage, targetLanguage)) {
            return scene;
        }
        if (sameLanguage(sourceLanguage, targetLanguage)) {
            scene.put("dialogueScript", canonicalDialogue);
            scene.put("exactDialogue", canonicalDialogue);
            scene.put("spokenDialogue", canonicalDialogue);
            scene.put("voiceover", canonicalDialogue);
            scene.put("captionText", canonicalDialogue);
            scene.put("sourceDialogueScript", canonicalDialogue);
            scene.put("sourceDialogueLanguage", sourceLanguage);
            scene.put("dialogueLanguage", targetLanguage);
            scene.put("languageCode", targetLanguageCode);
            scene.put("dialogueLocalizationStatus", "RESTORED_SOURCE");
            return scene;
        }
        boolean translate = booleanValue(
                request == null ? null : request.get("autoTranslateDialogue"),
                true
        );
        if (!translate) {
            return scene;
        }

        Map<String, Object> translationSource = copyMap(scene);
        translationSource.put("dialogueScript", canonicalDialogue);
        translationSource.put("exactDialogue", canonicalDialogue);
        translationSource.put("spokenDialogue", canonicalDialogue);
        translationSource.put("voiceover", canonicalDialogue);
        translationSource.put("captionText", canonicalDialogue);
        return localizeDialogueScenes(
                script,
                List.of(translationSource),
                sourceLanguage,
                targetLanguage,
                targetLanguageCode,
                jobId
        ).get(0);
    }

    List<Map<String, Object>> localizeDialogueScenes(
            CreatorScript script,
            List<Map<String, Object>> sourceScenes,
            String sourceLanguage,
            String targetLanguage,
            String targetLanguageCode,
            UUID jobId
    ) {
        List<Map<String, Object>> dialogueRows = new ArrayList<>();
        for (int index = 0; index < sourceScenes.size(); index++) {
            Map<String, Object> scene = sourceScenes.get(index);
            String dialogue = dialogueTextForScene(scene);
            if (dialogue.isBlank()) {
                continue;
            }
            dialogueRows.add(Map.of(
                    "id", sceneIdFor(scene, index + 1),
                    "sceneNumber", intValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), index + 1),
                    "durationSeconds", positiveInt(scene.get("durationSeconds"), 8),
                    "dialogueScript", dialogue
            ));
        }
        if (dialogueRows.isEmpty()) {
            return sourceScenes;
        }

        String renderedPrompt = """
                You are a senior short-form dialogue localization director.
                Translate every spoken line from %s into %s for a founder-led video.

                Source scenes JSON:
                %s

                Rules:
                - Return strict JSON only, with exactly one result for every supplied scene id.
                - Preserve meaning, factual claims, hook strength, emotion, CTA, names, numbers, and ordering.
                - Make the speech natural for a native %s speaker, not a literal word-for-word translation.
                - Keep each line concise enough to be spoken clearly inside its durationSeconds.
                - Do not add, remove, summarize, censor, or explain any claim.
                - For Hinglish, use natural Roman-script Hindi mixed with common English terms.
                - For Hindi, use natural Devanagari Hindi unless a brand or technical term is normally written in English.
                - Output only spoken dialogue. Do not add speaker labels, quotes, stage directions, or markdown.

                Return this exact shape:
                {
                  "scenes": [
                    {
                      "id": "same id as input",
                      "dialogueScript": "localized spoken line"
                    }
                  ]
                }
                """.formatted(sourceLanguage, targetLanguage, toJson(dialogueRows), targetLanguage);

        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("sourceLanguage", sourceLanguage);
        providerInput.put("targetLanguage", targetLanguage);
        providerInput.put("targetLanguageCode", targetLanguageCode);
        providerInput.put("scenes", dialogueRows);
        providerInput.put("renderedPrompt", renderedPrompt);

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                jobId,
                null
        );
        CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(
                PROMPT_SCREENPLAY_DIALOGUE_LOCALIZE,
                providerInput,
                usageContext
        );
        List<Map<String, Object>> localizedRows = mapListValue(firstValue(
                aiResponse.output().get("scenes"),
                aiResponse.output().get("translations"),
                aiResponse.output().get("localizedScenes")
        ));
        Map<String, Map<String, Object>> localizedById = new LinkedHashMap<>();
        for (Map<String, Object> row : localizedRows) {
            String id = firstText(row.get("id"), row.get("sceneId"));
            if (!id.isBlank()) {
                localizedById.put(id, row);
            }
        }

        List<Map<String, Object>> localizedScenes = new ArrayList<>();
        int dialogueIndex = 0;
        for (int index = 0; index < sourceScenes.size(); index++) {
            Map<String, Object> source = sourceScenes.get(index);
            String sourceDialogue = dialogueTextForScene(source);
            Map<String, Object> localized = new LinkedHashMap<>(source);
            if (!sourceDialogue.isBlank()) {
                String sceneId = sceneIdFor(source, index + 1);
                Map<String, Object> localizedRow = localizedById.get(sceneId);
                if ((localizedRow == null || localizedRow.isEmpty()) && dialogueIndex < localizedRows.size()) {
                    localizedRow = localizedRows.get(dialogueIndex);
                }
                String localizedDialogue = firstText(
                        localizedRow == null ? null : localizedRow.get("dialogueScript"),
                        localizedRow == null ? null : localizedRow.get("exactDialogue"),
                        localizedRow == null ? null : localizedRow.get("translatedDialogue"),
                        localizedRow == null ? null : localizedRow.get("text")
                );
                if (localizedDialogue.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Dialogue localization did not return every scene. No video generation was submitted."
                    );
                }
                localized.put("sourceDialogueScript", sourceDialogue);
                localized.put("dialogueScript", localizedDialogue);
                localized.put("exactDialogue", localizedDialogue);
                localized.put("spokenDialogue", localizedDialogue);
                localized.put("voiceover", localizedDialogue);
                localized.put("captionText", localizedDialogue);
                dialogueIndex++;
            }
            localized.put("sourceDialogueLanguage", sourceLanguage);
            localized.put("dialogueLanguage", targetLanguage);
            localized.put("languageCode", targetLanguageCode);
            localized.put("dialogueLocalizationStatus", sourceDialogue.isBlank() ? "NO_DIALOGUE" : "COMPLETED");
            localizedScenes.add(localized);
        }

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("sourceLanguage", sourceLanguage);
        outputPayload.put("targetLanguage", targetLanguage);
        outputPayload.put("targetLanguageCode", targetLanguageCode);
        outputPayload.put("scenes", localizedRows);
        outputPayload.put("providerOutput", aiResponse.output());
        CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .jobId(jobId)
                .promptTemplateKey(PROMPT_SCREENPLAY_DIALOGUE_LOCALIZE)
                .promptTemplateVersion(1)
                .renderedPrompt(renderedPrompt)
                .inputSnapshot(providerInput)
                .provider(creatorAiService.providerName())
                .model(creatorAiService.modelName())
                .outputPayload(outputPayload)
                .tokenMetadata(aiResponse.tokenMetadata())
                .costMetadata(aiResponse.costMetadata())
                .status("COMPLETED")
                .completedAt(OffsetDateTime.now())
                .build());
        localizedScenes.forEach(scene -> {
            scene.put("dialogueLocalizationPromptRunId", promptRun.getId().toString());
            scene.put("dialogueTranslationApplied", true);
        });
        creatorAiService.publishBillingDebit(
                PROMPT_SCREENPLAY_DIALOGUE_LOCALIZE,
                aiResponse,
                usageContext.withPromptRunId(promptRun.getId())
        );
        return localizedScenes;
    }

    Map<String, Object> requestSceneOverride(Map<String, Object> request, Map<String, Object> source, int defaultSceneNumber) {
        List<Map<String, Object>> requestScenes = mapListValue(request == null ? null : request.get("scenes"));
        if (requestScenes.isEmpty()) {
            return new LinkedHashMap<>();
        }
        String sourceId = firstText(
                source == null ? null : source.get("id"),
                source == null ? null : source.get("sceneId"),
                source == null ? null : source.get("scene_id"),
                source == null ? null : source.get("shotId"),
                source == null ? null : source.get("shot_id"),
                "scene-" + defaultSceneNumber
        );
        String sourceSceneNumber = stringValue(firstValue(
                source == null ? null : source.get("sceneNumber"),
                source == null ? null : source.get("scene_number"),
                defaultSceneNumber
        ), "");
        String sourceShotNumber = stringValue(firstValue(
                source == null ? null : source.get("shotNumber"),
                source == null ? null : source.get("shot_number"),
                defaultSceneNumber
        ), "");
        for (Map<String, Object> override : requestScenes) {
            String overrideId = firstText(override.get("id"), override.get("sceneId"), override.get("scene_id"), override.get("shotId"), override.get("shot_id"));
            String overrideSceneNumber = stringValue(firstValue(override.get("sceneNumber"), override.get("scene_number")), "");
            String overrideShotNumber = stringValue(firstValue(override.get("shotNumber"), override.get("shot_number")), "");
            if ((!overrideId.isBlank() && overrideId.equals(sourceId))
                    || (!overrideSceneNumber.isBlank() && overrideSceneNumber.equals(sourceSceneNumber))
                    || (!overrideShotNumber.isBlank() && overrideShotNumber.equals(sourceShotNumber))) {
                return copyMap(override);
            }
        }
        return new LinkedHashMap<>();
    }

    Map<String, Object> renderManifest(
            String provider,
            String model,
            Map<String, Object> request,
            Map<String, Object> scriptPayload,
            List<Map<String, Object>> scenes
    ) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("provider", provider);
        manifest.put("model", model);
        manifest.put("sceneCount", scenes.size());
        manifest.put("maxClipSeconds", intValue(request.get("maxClipSeconds"), 15));
        manifest.put("rateLimitPolicy", rateLimitPolicy(provider));
        Map<String, Object> videoFinishingPlan = withAudioMixStandards(firstMap(request.get("videoFinishingPlan"), scriptPayload.get("videoFinishingPlan")));
        Map<String, Object> soundDesignPlan = withAudioMixStandards(firstMap(request.get("soundDesignPlan"), scriptPayload.get("soundDesignPlan")));
        Map<String, Object> imageLedAdPlan = imageLedAdPlan(request, scriptPayload, videoFinishingPlan);
        Map<String, Object> audioProductionPlan = audioProductionPlan(request, scriptPayload, soundDesignPlan, videoFinishingPlan);
        Map<String, Object> editingPlan = editingPlan(request, scriptPayload, videoFinishingPlan, soundDesignPlan, imageLedAdPlan, audioProductionPlan);
        manifest.put("videoFinishingPlan", videoFinishingPlan);
        manifest.put("soundDesignPlan", soundDesignPlan);
        manifest.put("imageLedAdPlan", imageLedAdPlan);
        manifest.put("audioProductionPlan", audioProductionPlan);
        manifest.put("editingPlan", editingPlan);
        manifest.put("editorHandoffPlan", editingPlan);
        manifest.put("recommendedEditingTools", firstValue(editingPlan.get("recommendedTools"), editingPlan.get("toolsToUse")));
        manifest.put("audioMixStandards", audioMixStandards(videoFinishingPlan.get("audioMixStandards"), soundDesignPlan.get("audioMixStandards"), request.get("audioMixStandards"), scriptPayload.get("audioMixStandards")));
        manifest.put("srtFile", firstMap(request.get("srtFile"), scriptPayload.get("srtFile")));
        manifest.put("requiresProviderAdapter", true);
        manifest.put("providerAdapterStatus", "REQUEST_PAYLOAD_READY");
        return manifest;
    }

    private Map<String, Object> applySceneEdit(
            Map<String, Object> original,
            Map<String, Object> edited,
            Map<String, Object> run,
            Map<String, Object> request
    ) {
        Map<String, Object> result = new LinkedHashMap<>(original);
        result.putAll(copyMap(edited));
        result.put("id", firstText(original.get("id"), original.get("sceneId"), result.get("id")));
        result.put("sceneId", firstText(original.get("sceneId"), original.get("id"), result.get("sceneId")));
        result.put("sceneNumber", intValue(firstValue(original.get("sceneNumber"), result.get("sceneNumber")), 1));
        result.put("shotNumber", intValue(firstValue(original.get("shotNumber"), result.get("shotNumber"), result.get("sceneNumber")), 1));
        result.put("durationSeconds", positiveInt(firstValue(result.get("durationSeconds"), original.get("durationSeconds")), 15));
        String provider = providerForSceneGeneration(result, run, request);
        String model = modelForSceneGeneration(provider, result, run, request);
        result.put("provider", provider);
        result.put("targetProvider", provider);
        result.put("model", model);
        result.put("generationMode", generationModeFor(result, run, request));
        result.put("providerPrompt", firstText(
                result.get("providerPrompt"),
                result.get("seedancePrompt"),
                result.get("sketchPrompt"),
                result.get("visualPrompt"),
                result.get("action"),
                original.get("providerPrompt")
        ));
        result.put("prompt", firstText(result.get("providerPrompt"), result.get("prompt"), result.get("action")));
        return result;
    }

    private void appendRevision(
            Map<String, Object> scene,
            String message,
            Map<String, Object> beforeScene,
            SceneEditResult editResult,
            Map<String, Object> ragContext
    ) {
        List<Map<String, Object>> revisions = mapListValue(scene.get("revisions"));
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("id", UUID.randomUUID().toString());
        revision.put("role", "user");
        revision.put("message", message);
        revision.put("createdAt", OffsetDateTime.now().toString());
        revision.put("status", editResult.errorMessage() == null ? "APPLIED" : "APPLIED_WITH_FALLBACK");
        revision.put("promptBefore", firstText(beforeScene.get("providerPrompt"), beforeScene.get("prompt"), beforeScene.get("seedancePrompt")));
        revision.put("promptAfter", firstText(scene.get("providerPrompt"), scene.get("prompt"), scene.get("seedancePrompt")));
        revision.put("promptRunId", editResult.promptRunId() == null ? null : editResult.promptRunId().toString());
        revision.put("aiOutput", editResult.providerOutput());
        revision.put("errorMessage", editResult.errorMessage());
        revision.put("ragContext", ragContext);
        revisions.add(revision);
        scene.put("revisions", revisions);
        scene.put("chatRevisions", revisions);
    }

    Map<String, Object> buildRagContext(
            CreatorScript script,
            Map<String, Object> run,
            List<Map<String, Object>> scenes,
            int sceneIndex,
            Map<String, Object> request
    ) {
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> current = sceneIndex >= 0 && sceneIndex < scenes.size() ? copyMap(scenes.get(sceneIndex)) : Map.of();
        Map<String, Object> previous = sceneIndex > 0 ? compactScene(scenes.get(sceneIndex - 1)) : Map.of();
        Map<String, Object> next = sceneIndex + 1 < scenes.size() ? compactScene(scenes.get(sceneIndex + 1)) : Map.of();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("script", Map.of(
                "scriptId", script.getId().toString(),
                "title", defaultString(script.getTitle(), stringValue(scriptPayload.get("projectTitle"), "")),
                "durationSeconds", script.getDurationSeconds() == null ? 0 : script.getDurationSeconds(),
                "categoryCode", defaultString(script.getCategoryCode(), stringValue(scriptPayload.get("category"), "")),
                "screenType", firstText(script.getScreenType(), scriptPayload.get("screenType")),
                "productionStyle", normalizeProductionStyle(firstText(request == null ? null : request.get("productionStyle"), run == null ? null : run.get("productionStyle"), scriptPayload.get("productionStyle"))),
                "dialogueLanguage", firstText(script.getDialogueLanguage(), scriptPayload.get("dialogueLanguage"))
        ));
        context.put("currentScene", compactScene(current));
        context.put("previousScene", previous);
        context.put("nextScene", next);
        context.put("videoPacingProfile", firstMap(request == null ? null : request.get("videoPacingProfile"), run == null ? null : run.get("videoPacingProfile"), scriptPayload.get("videoPacingProfile")));
        context.put("videoConsistencyBible", firstMap(request == null ? null : request.get("videoConsistencyBible"), run == null ? null : run.get("videoConsistencyBible"), scriptPayload.get("videoConsistencyBible")));
        context.put("seedancePromptStrategy", firstMap(request == null ? null : request.get("seedancePromptStrategy"), run == null ? null : run.get("seedancePromptStrategy"), scriptPayload.get("seedancePromptStrategy")));
        context.put("srtFile", firstMap(request == null ? null : request.get("srtFile"), run == null ? null : run.get("srtFile"), scriptPayload.get("srtFile")));
        context.put("srtCues", srtCuesForScene(firstList(request == null ? null : request.get("srtCues"), run == null ? null : run.get("srtCues"), scriptPayload.get("srtCues")), current));
        context.put("videoFinishingPlan", firstMap(request == null ? null : request.get("videoFinishingPlan"), run == null ? null : run.get("videoFinishingPlan"), scriptPayload.get("videoFinishingPlan")));
        context.put("soundDesignPlan", firstMap(request == null ? null : request.get("soundDesignPlan"), run == null ? null : run.get("soundDesignPlan"), scriptPayload.get("soundDesignPlan")));
        context.put("imageLedAdPlan", firstMap(request == null ? null : request.get("imageLedAdPlan"), run == null ? null : run.get("imageLedAdPlan"), scriptPayload.get("imageLedAdPlan")));
        context.put("audioProductionPlan", firstMap(request == null ? null : request.get("audioProductionPlan"), run == null ? null : run.get("audioProductionPlan"), scriptPayload.get("audioProductionPlan")));
        context.put("editingPlan", firstMap(request == null ? null : request.get("editingPlan"), run == null ? null : run.get("editingPlan"), scriptPayload.get("editingPlan")));
        context.put("storyCharacters", firstList(scriptPayload.get("storyCharacters"), scriptPayload.get("characters")));
        context.put("brandContext", firstMap(scriptPayload.get("brandContext")));
        context.put("creatorContext", firstMap(scriptPayload.get("creatorContext")));
        context.put("retrievedKeys", List.of(
                "current_scene",
                "adjacent_scenes",
                "video_consistency_bible",
                "video_pacing_profile",
                "seedance_prompt_strategy",
                "srt_file_and_cues",
                "sound_design_plan",
                "story_characters"
        ));
        return context;
    }

    private String buildSceneEditPrompt(
            Map<String, Object> run,
            Map<String, Object> scene,
            String message,
            Map<String, Object> ragContext
    ) {
        String provider = normalizeVideoProvider(firstText(run == null ? null : run.get("provider"), scene.get("provider")));
        return """
                You are improving one scene inside an approved short-video screenplay.

                Return exactly one valid JSON object:
                {"shot": { ...full updated scene JSON... }}

                User edit request:
                %s

                Provider target:
                %s

                Rules:
                - This is a targeted enhancement to an already-approved plan, not a rewrite. Change ONLY what the user's edit request asks for; every other field (wardrobe, set, props, camera, lighting, expression, emotion, emotionIntensity, bodyLanguage, dialogue content) must come back exactly as it is in the current scene JSON below unless the request explicitly asks to change it too.
                - Keep the same id, sceneNumber, shotNumber, durationSeconds, startTime, and endTime unless the user explicitly asked for timing changes.
                - Preserve continuity from the RAG context: same characters, wardrobe, geography, lighting logic, captions, tone, and adjacent-scene motion.
                - Update providerPrompt/prompt for the selected video model.
                - If the scene is full AI, generationMode must stay ai_generated.
                - If hybrid and the user asks for a person/talking head, generationMode may be talking_head; otherwise prefer ai_generated for B-roll/visual-only shots.
                - Keep captions compatible with the SRT cues and caption style.
                - If (and only if) the user's edit request asks to change the spoken/dialogue language for this scene, translate dialogue, captionTrack, and srtCues into the requested language, preserving meaning, tone, and timing, and set dialogueLanguage to the requested language - this chat is the only place a single scene's dialogue language can be changed, so honor this request explicitly when asked.
                - Include short editingNotes explaining the change.

                Current scene JSON:
                %s

                Retrieved RAG context:
                %s
                """.formatted(
                message,
                provider,
                truncate(toJson(scene), 6000),
                truncate(toJson(ragContext), 9000)
        );
    }

    @Override
    public Map<String, Object> buildProviderRequest(Map<String, Object> run, Map<String, Object> scene, Map<String, Object> request) {
        String provider = providerForSceneGeneration(scene, run, request);
        String model = modelForSceneGeneration(provider, scene, run, request);
        return buildProviderRequestForScene(provider, model, scene, request == null ? Map.of() : request, copyMap(run));
    }

    String providerForSceneGeneration(Map<String, Object> scene, Map<String, Object> runOrRequest, Map<String, Object> request) {
        String generationMode = generationModeFor(scene == null ? Map.of() : scene, runOrRequest == null ? Map.of() : runOrRequest, request);
        ScreenplaySceneView sceneView = SceneViewMapper.sceneView(scene, objectMapper);
        if ("talking_head".equals(generationMode)) {
            return normalizeVideoProvider(avatarProviderFrom(
                    request == null ? null : request.get("avatarProviderMode"),
                    request == null ? null : request.get("avatarProvider"),
                    sceneView.avatarProviderMode(),
                    sceneView.avatarProvider(),
                    runOrRequest == null ? null : runOrRequest.get("avatarProviderMode"),
                    runOrRequest == null ? null : runOrRequest.get("avatarProvider"),
                    firstMap(runOrRequest == null ? null : runOrRequest.get("founderAvatarProfile")).get("avatarProviderMode")
            ));
        }
        return normalizeVideoProvider(firstText(
                request == null ? null : request.get("provider"),
                request == null ? null : request.get("targetProvider"),
                sceneView.provider(),
                runOrRequest == null ? null : runOrRequest.get("provider")
        ));
    }

    String modelForSceneGeneration(String provider, Map<String, Object> scene, Map<String, Object> runOrRequest, Map<String, Object> request) {
        String normalizedProvider = normalizeVideoProvider(provider);
        if ("synthesia".equals(normalizedProvider)) {
            return modelForProvider(normalizedProvider, firstText(
                    request == null ? null : request.get("synthesiaModel"),
                    scene == null ? null : scene.get("synthesiaModel"),
                    firstMap(runOrRequest == null ? null : runOrRequest.get("founderAvatarProfile")).get("synthesiaModel"),
                    "synthesia-avatar-video"
            ));
        }
        if ("dalai_llama".equals(normalizedProvider)) {
            Map<String, Object> profile = founderAvatarProfile(request, runOrRequest, firstMap(runOrRequest == null ? null : runOrRequest.get("creatorContext")));
            Map<String, Object> localModels = firstMap(profile.get("localModels"));
            return modelForProvider(normalizedProvider, firstText(
                    request == null ? null : request.get("localTalkingAvatarModel"),
                    request == null ? null : request.get("talkingAvatarModel"),
                    localModels.get("talkingAvatarModel"),
                    scene == null ? null : scene.get("model"),
                    "fal_heygen_avatar4"
            ));
        }
        return modelForProvider(normalizedProvider, firstText(
                request == null ? null : request.get("model"),
                request == null ? null : request.get("videoModel"),
                scene == null ? null : scene.get("model"),
                runOrRequest == null ? null : runOrRequest.get("model")
        ));
    }

    // Ephemeral, request-scoped only - never persisted onto the scene record. Matches the
    // script's cast mappings (contextPayload.characterCastMappings, populated in the run
    // assembly step) against this scene's free-text character fields by name, since no
    // structured "which characters appear in scene N" field exists in the scene JSON today.
    List<Map<String, Object>> castCharactersForScene(Map<String, Object> scene, Map<String, Object> contextPayload) {
        return new CastCharacterResolver().castCharactersForScene(scene, contextPayload);
    }

    /**
     * Provider-agnostic providerRequest assembly, extracted to ProviderRequestBuilder (Builder
     * pattern - each resolve*() step is a section of what used to be a single 394-line method) so
     * this stays a one-line delegation instead of the largest, least-readable method in the class.
     */
    Map<String, Object> buildProviderRequestForScene(
            String provider,
            String model,
            Map<String, Object> scene,
            Map<String, Object> request,
            Map<String, Object> contextPayload
    ) {
        return new ProviderRequestBuilder(this, provider, model, scene, request, contextPayload).build();
    }

    List<Map<String, Object>> sourceScenes(CreatorScript script, Map<String, Object> request) {
        List<Map<String, Object>> persistedShots = persistedScriptShotScenes(script);
        if (!persistedShots.isEmpty()) {
            return enrichScenesWithStoryboardReferences(script, persistedShots, request);
        }
        List<Map<String, Object>> scriptShots = mapListValue(script.getShots());
        if (!scriptShots.isEmpty()) {
            return enrichScenesWithStoryboardReferences(script, scriptShots, request);
        }
        List<Map<String, Object>> payloadShots = mapListValue(script.getScriptPayload() == null ? null : script.getScriptPayload().get("shots"));
        if (!payloadShots.isEmpty()) {
            return enrichScenesWithStoryboardReferences(script, payloadShots, request);
        }
        List<Map<String, Object>> requestScenes = mapListValue(request == null ? null : request.get("scenes"));
        if (!requestScenes.isEmpty()) {
            return enrichScenesWithStoryboardReferences(script, requestScenes, request);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenplay scenes are missing for video generation.");
    }

    private List<Map<String, Object>> persistedScriptShotScenes(CreatorScript script) {
        if (script == null || script.getId() == null || scriptShotRepository == null) {
            return List.of();
        }
        return scriptShotRepository.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(script.getId())
                .stream()
                .map(this::persistedScriptShotScene)
                .toList();
    }

    private Map<String, Object> persistedScriptShotScene(CreatorScriptShot shot) {
        if (shot == null) {
            return new LinkedHashMap<>();
        }
        int shotNumber = positiveInt(shot.getShotNumber(), 1);
        Map<String, Object> scene = copyMap(shot.getShotPayload());
        scene.put("id", "shot-" + shotNumber);
        scene.put("sceneId", "shot-" + shotNumber);
        scene.put("sceneNumber", positiveInt(shot.getSceneNumber(), shotNumber));
        scene.put("shotNumber", shotNumber);
        scene.put("screenplayShotNumber", shotNumber);
        scene.put("screenplayShotRecordId", shot.getId() == null ? "" : shot.getId().toString());
        scene.put("dialogueSource", "creator_script_shots");
        putIfBlank(scene, "title", shot.getTitle());
        putIfBlank(scene, "beatTitle", shot.getBeatTitle());
        putIfBlank(scene, "purpose", shot.getPurpose());
        putIfBlank(scene, "shotType", shot.getShotType());
        putIfBlank(scene, "coverageType", shot.getCoverageType());
        putIfBlank(scene, "screenDirection", shot.getScreenDirection());
        if (shot.getStartTime() != null) {
            scene.put("startSeconds", shot.getStartTime());
        }
        if (shot.getEndTime() != null) {
            scene.put("endSeconds", shot.getEndTime());
        }
        if (shot.getDurationSeconds() != null) {
            scene.put("durationSeconds", shot.getDurationSeconds());
        }
        Map<String, Object> structuredDialogue = copyMap(shot.getDialogue());
        if (!structuredDialogue.isEmpty()) {
            scene.put("dialogue", structuredDialogue);
        }
        String spokenDialogue = dialogueObjectText(structuredDialogue);
        if (spokenDialogue.isBlank()) {
            spokenDialogue = dialogueTextForScene(scene);
        }
        applySceneDialogue(scene, spokenDialogue);
        return scene;
    }

    public static void applySceneDialogue(Map<String, Object> scene, String dialogue) {
        String normalized = normalizeDialogueText(dialogue);
        if (scene == null || normalized.isBlank()) {
            return;
        }
        scene.put("dialogueScript", normalized);
        scene.put("exactDialogue", normalized);
        scene.put("spokenDialogue", normalized);
        scene.put("spokenText", normalized);
        scene.put("voiceover", normalized);
        scene.put("voiceOver", normalized);
        scene.put("captionText", normalized);
        scene.put("dialogueCoverageRequired", true);
    }

    private List<Map<String, Object>> reconcilePreparedRunScenes(
            CreatorScript script,
            Map<String, Object> run,
            List<Map<String, Object>> existingScenes
    ) {
        boolean preparedWorkspace = booleanValue(run == null ? null : run.get("prepareOnly"), false)
                || "scene_by_scene".equalsIgnoreCase(firstText(run == null ? null : run.get("generationWorkflow")));
        if (!preparedWorkspace || existingScenes == null || existingScenes.isEmpty()) {
            return existingScenes == null ? List.of() : existingScenes;
        }
        List<Map<String, Object>> persistedScenes = persistedScriptShotScenes(script);
        if (persistedScenes.isEmpty()) {
            return existingScenes;
        }
        boolean avatarDialogueRun = avatarSceneDialogueService != null
                && avatarDialogueSyncGateway.isAvatarDialogueRun(run, existingScenes);
        UUID videoRunId = uuidValue(run == null ? null : run.get("runId"));
        Map<Integer, CreatorAvatarSceneDialogue> avatarSources = avatarDialogueRun
                ? avatarSceneDialogueService.currentSources(
                        script.getTenantId(),
                        script.getUserId(),
                        videoRunId
                )
                : Map.of();
        Map<Integer, AvatarSceneDialogueService.DialogueDraft> avatarDrafts = new LinkedHashMap<>();
        if (avatarDialogueRun) {
            avatarSceneDialogueService.extractSourceDialogues(script)
                    .forEach(draft -> avatarDrafts.putIfAbsent(draft.sceneNumber(), draft));
        }

        Map<Integer, Map<String, Object>> existingBySceneNumber = new LinkedHashMap<>();
        for (int index = 0; index < existingScenes.size(); index++) {
            Map<String, Object> existing = existingScenes.get(index);
            int sceneNumber = positiveInt(firstValue(
                    existing == null ? null : existing.get("sceneNumber"),
                    existing == null ? null : existing.get("shotNumber")
            ), index + 1);
            existingBySceneNumber.putIfAbsent(sceneNumber, existing);
        }

        List<Map<String, Object>> reconciled = new ArrayList<>();
        String sourceLanguage = firstText(
                script == null ? null : script.getDialogueLanguage(),
                script == null ? null : firstMap(script.getScriptPayload()).get("dialogueLanguage"),
                run == null ? null : run.get("sourceDialogueLanguage")
        );
        for (int index = 0; index < persistedScenes.size(); index++) {
            int sceneNumber = index + 1;
            Map<String, Object> existing = existingBySceneNumber.get(sceneNumber);
            if (existing == null && index < existingScenes.size()) {
                existing = existingScenes.get(index);
            }
            if (index == 0 && existing != null && !existing.isEmpty()) {
                reconciled.add(copyMap(existing));
                continue;
            }

            Map<String, Object> canonical = copyMap(persistedScenes.get(index));
            Map<String, Object> merged = copyMap(existing);
            merged.putAll(canonical);
            copySceneRuntimeState(existing, merged);
            merged.remove("sourceSceneNumber");
            merged.remove("sourceShotNumber");
            merged.remove("dialoguePart");
            merged.remove("dialoguePartCount");
            merged.remove("storyboardSegmentation");

            CreatorAvatarSceneDialogue avatarSource = avatarSources.get(sceneNumber);
            AvatarSceneDialogueService.DialogueDraft avatarDraft = avatarDrafts.get(sceneNumber);
            String persistedDialogue = firstText(
                    avatarSource == null ? null : avatarSource.getDialogueText(),
                    avatarDraft == null ? null : avatarDraft.dialogueText(),
                    dialogueTextForScene(canonical)
);
            String canonicalLanguage = firstText(
                    avatarSource == null ? null : avatarSource.getLanguage(),
                    avatarDraft == null ? null : avatarDraft.language(),
                    sourceLanguage
            );
            String targetLanguage = firstText(
                    existing == null ? null : existing.get("dialogueLanguage"),
                    run == null ? null : run.get("dialogueLanguage"),
                    sourceLanguage
            );
            CreatorAvatarSceneDialogue selectedAvatarDialogue = null;
            if (avatarSource != null) {
                selectedAvatarDialogue = sameLanguage(canonicalLanguage, targetLanguage)
                        ? avatarSource
                        : avatarSceneDialogueService
                                .currentTranslation(avatarSource.getRootDialogueId(), targetLanguage)
                                .orElse(null);
            }
            boolean preserveLocalizedDialogue = avatarDialogueRun
                    ? selectedAvatarDialogue != null && !sameLanguage(canonicalLanguage, targetLanguage)
                    : existing != null
                    && !sameLanguage(sourceLanguage, targetLanguage)
                    && (
                            booleanValue(existing.get("dialogueTranslationApplied"), false)
                                    || "COMPLETED".equalsIgnoreCase(firstText(existing.get("dialogueLocalizationStatus")))
                    )
                    && !dialogueTextForScene(existing).isBlank();
            if (avatarDialogueRun && selectedAvatarDialogue != null) {
                avatarDialogueSyncGateway.applyRecord(merged, avatarSource, selectedAvatarDialogue);
            } else if (preserveLocalizedDialogue) {
                copySceneLocalizationState(existing, merged);
            } else {
                applySceneDialogue(merged, persistedDialogue);
                merged.put("dialogueLanguage", firstText(canonicalLanguage, targetLanguage));
                merged.put("sourceDialogueLanguage", firstText(canonicalLanguage, targetLanguage));
                merged.put("dialogueLocalizationStatus", "NOT_REQUIRED");
                merged.put("dialogueTranslationApplied", false);
                if (avatarSource != null) {
                    avatarDialogueSyncGateway.applyRecord(merged, avatarSource, avatarSource);
                } else if (avatarDraft != null) {
                    merged.put("dialogueSpeaker", avatarDraft.speaker());
                    merged.put("dialogueSourceKind", avatarDraft.sourceKind());
                    merged.put("dialogueSourcePath", avatarDraft.sourcePath());
                    merged.put("dialogueVariants", List.of(draftDialogueVariant(avatarDraft, true)));
                    merged.put("selectedDialogueLanguage", avatarDraft.language());
                }
            }
            merged.put("sourceDialogueScript", persistedDialogue);
            merged.put(
                    "dialogueSource",
                    avatarDialogueRun
                            ? avatarSource == null ? "avatar_screenplay_dto" : "creator_avatar_scene_dialogues"
                            : "creator_script_shots"
);

            String effectiveDialogue = dialogueTextForScene(merged);
            String clonedDialogue = firstText(existing == null ? null : existing.get("dialogueCloneText"));
            boolean clonedDialogueMatches = !clonedDialogue.isBlank()
                    && normalizeDialogueText(clonedDialogue).equals(normalizeDialogueText(effectiveDialogue));
            if (avatarDialogueRun) {
                merged.put("dialogueCloneMatchesSelectedDialogue", clonedDialogueMatches);
                merged.put(
                        "dialogueCloneSelectionRequired",
                        !clonedDialogue.isBlank() && !clonedDialogueMatches
                );
            } else if (!clonedDialogue.isBlank() && !clonedDialogueMatches) {
                clearSceneDialogueCloneState(merged);
            }
            syncProviderRequestDialogue(merged, effectiveDialogue);
            reconciled.add(merged);
        }
        return reconciled;
    }

    void synchronizeAvatarDialogueSources(CreatorScript script, Map<String, Object> run) {
        if (avatarSceneDialogueService == null || script == null || !avatarDialogueSyncGateway.isAvatarDialogueRun(run, mapListValue(run.get("scenes")))) {
            return;
        }
        UUID videoRunId = uuidValue(run.get("runId"));
        if (videoRunId == null) {
            return;
        }
        Map<Integer, CreatorAvatarSceneDialogue> sources = avatarSceneDialogueService.synchronizeSources(
                script,
                videoRunId
        );
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        for (int index = 0; index < scenes.size(); index++) {
            Map<String, Object> scene = copyMap(scenes.get(index));
            int sceneNumber = positiveInt(
                    firstValue(scene.get("sceneNumber"), scene.get("shotNumber")),
                    index + 1
            );
            CreatorAvatarSceneDialogue source = sources.get(sceneNumber);
            if (source == null) {
                continue;
            }
            UUID promptRunId = uuidValue(scene.get("dialogueLocalizationPromptRunId"));
            String targetLanguage = firstText(scene.get("dialogueLanguage"), run.get("dialogueLanguage"));
            boolean generatedByCurrentLocalization = promptRunId != null
                    && !sameLanguage(source.getLanguage(), targetLanguage)
                    && "COMPLETED".equalsIgnoreCase(firstText(scene.get("dialogueLocalizationStatus")))
                    && !dialogueTextForScene(scene).isBlank();
            if (generatedByCurrentLocalization) {
                CreatorAvatarSceneDialogue translated = avatarSceneDialogueService.saveTranslation(
                        source,
                        targetLanguage,
                        firstText(scene.get("languageCode"), languageCodeFor(targetLanguage)),
                        dialogueTextForScene(scene),
                        promptRunId,
                        uuidValue(run.get("jobId")),
                        creatorAiService == null ? null : creatorAiService.providerName(),
                        creatorAiService == null ? null : creatorAiService.modelName()
                );
                avatarDialogueSyncGateway.applyRecord(scene, source, translated);
                scenes.set(index, scene);
            }
        }
        run.put("scenes", reconcilePreparedRunScenes(script, run, scenes));
        run.put("sceneClips", run.get("scenes"));
    }

    private Map<String, Object> draftDialogueVariant(
            AvatarSceneDialogueService.DialogueDraft draft,
            boolean selected
    ) {
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("language", draft.language());
        variant.put("languageKey", avatarSceneDialogueService.languageKey(draft.language()));
        variant.put("languageCode", avatarSceneDialogueService.languageCode(draft.language()));
        variant.put("dialogueText", draft.dialogueText());
        variant.put("speaker", draft.speaker());
        variant.put("source", true);
        variant.put("selected", selected);
        return variant;
    }

    private void copySceneRuntimeState(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || source.isEmpty()) {
            return;
        }
        List.of(
                "status", "provider", "targetProvider", "model", "brollProvider", "brollModel",
                "maxClipSeconds", "generationMode", "avatarProviderMode", "founderAvatarProfile",
                "providerRequest", "queuedAt", "completedAt", "updatedAt", "failedAt", "message",
                "bucket", "objectKey", "object_key", "contentType", "sizeBytes", "publicUrl",
                "signedUrl", "videoUrl", "clipUrl", "operationId", "providerOperationId", "taskId",
                "dialogueAudio", "voiceTrack", "dialogueCloneStatus", "dialogueCloneAccepted",
                "dialogueCloneText", "dialogueCloneLanguage", "dialogueCloneLanguageCode",
                "dialogueCloneFingerprint", "dialogueCloneVoiceModel", "dialogueCloneMethod",
                "dialogueCloneGeneratedAt", "dialogueCloneError", "avatarPortraitAsset",
                "avatarPortraitUrl", "revisions", "accepted", "clipAccepted", "reviewAccepted",
                "reviewStatus", "productionImage", "productionImageUrl", "generatedProductImageUrl",
                "imageAnchorUrl", "productImageAsset", "productImageUrl", "generatedProductImageAssets"
        ).forEach(key -> copyIfPresent(target, source, key));
    }

    private void copySceneLocalizationState(Map<String, Object> source, Map<String, Object> target) {
        List.of(
                "localizedDialogue", "dialogueScript", "exactDialogue", "spokenDialogue", "spokenText",
                "voiceover", "voiceOver", "captionText", "dialogueLanguage", "languageCode",
                "sourceDialogueLanguage", "dialogueLocalizationStatus", "dialogueTranslationApplied"
        ).forEach(key -> copyIfPresent(target, source, key));
    }

    private void clearSceneDialogueCloneState(Map<String, Object> scene) {
        List.of(
                "dialogueAudio", "voiceTrack", "dialogueCloneText", "dialogueCloneLanguage",
                "dialogueCloneLanguageCode", "dialogueCloneFingerprint", "dialogueCloneVoiceModel",
                "dialogueCloneMethod", "dialogueCloneGeneratedAt", "dialogueCloneError"
        ).forEach(scene::remove);
        scene.put("dialogueCloneStatus", "NOT_REQUESTED");
        scene.put("dialogueCloneAccepted", false);
    }

    private void syncProviderRequestDialogue(Map<String, Object> scene, String dialogue) {
        Map<String, Object> providerRequest = copyMap(scene == null ? null : scene.get("providerRequest"));
        if (providerRequest.isEmpty()) {
            return;
        }
        providerRequest.put("dialogueScript", dialogue);
        providerRequest.put("exactDialogue", dialogue);
        providerRequest.put("spokenText", dialogue);
        providerRequest.put("captionText", dialogue);
        providerRequest.put("dialogueCoverageRequired", !dialogue.isBlank());
        scene.put("providerRequest", providerRequest);
    }

    List<Map<String, Object>> avatarScriptScenes(
            List<Map<String, Object>> screenplayScenes,
            String avatarScript
    ) {
        String script = normalizeDialogueText(avatarScript);
        if (script.isBlank()) {
            return screenplayScenes == null ? List.of() : screenplayScenes;
        }
        Map<String, Object> scene = screenplayScenes == null || screenplayScenes.isEmpty()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(screenplayScenes.get(0));
        scene.remove("id");
        scene.remove("sceneId");
        scene.remove("scene_id");
        scene.put("sceneNumber", 1);
        scene.put("shotNumber", 1);
        scene.put("title", "Avatar video script");
        scene.put("generationMode", "talking_head");
        scene.put("dialogueScript", script);
        scene.put("exactDialogue", script);
        scene.put("spokenDialogue", script);
        scene.put("spokenText", script);
        scene.put("voiceover", script);
        scene.put("voiceOver", script);
        scene.put("captionText", script);
        scene.put("dialogue", Map.of("line", script));
        scene.put("durationSeconds", estimatedDialogueSeconds(script));
        scene.put("avatarScriptOverride", true);
        scene.put("providerPrompt", firstText(
                scene.get("providerPrompt"),
                scene.get("visualPrompt"),
                scene.get("action"),
                "Use the uploaded creator video with locked identity, framing, wardrobe, background, and lighting."
        ));
        return List.of(scene);
    }

    List<Map<String, Object>> splitScenesForModelCapability(List<Map<String, Object>> scenes, int maxClipSeconds) {
        if (scenes == null || scenes.isEmpty()) {
            return List.of();
        }
        int safeMaxClipSeconds = Math.max(1, maxClipSeconds);
        int maxWordsPerPart = Math.max(6, (int) Math.floor(Math.max(1, safeMaxClipSeconds - 1) * 2.4d));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> rawScene : scenes) {
            Map<String, Object> source = rawScene == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawScene);
            String dialogue = dialogueTextForScene(source);
            List<String> chunks = dialogueChunks(dialogue, maxWordsPerPart);
            if (chunks.size() <= 1 || estimatedDialogueSeconds(dialogue) <= safeMaxClipSeconds) {
                result.add(capabilityScene(source, result.size() + 1, null, 1, 1, safeMaxClipSeconds));
                continue;
            }
            int sourceSceneNumber = positiveInt(firstValue(
                    source.get("sceneNumber"),
                    source.get("scene_number"),
                    source.get("shotNumber"),
                    source.get("shot_number")
            ), result.size() + 1);
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                Map<String, Object> splitScene = new LinkedHashMap<>(source);
                splitScene.put("sourceSceneNumber", sourceSceneNumber);
                splitScene.put("sourceShotNumber", positiveInt(firstValue(source.get("shotNumber"), source.get("shot_number")), sourceSceneNumber));
                splitScene.put("dialoguePart", index + 1);
                splitScene.put("dialoguePartCount", chunks.size());
                splitScene.put("dialogueScript", chunk);
                splitScene.put("exactDialogue", chunk);
                splitScene.put("spokenDialogue", chunk);
                splitScene.put("spokenText", chunk);
                splitScene.put("voiceover", chunk);
                splitScene.put("voiceOver", chunk);
                splitScene.put("captionText", chunk);
                splitScene.put("dialogue", Map.of("line", chunk));
                splitScene.put("title", firstText(source.get("title"), source.get("beatTitle"), "Scene " + sourceSceneNumber) + " - Part " + (index + 1));
                splitScene.put("durationSeconds", Math.max(1, Math.min(safeMaxClipSeconds, estimatedDialogueSeconds(chunk))));
                splitScene.put("providerPrompt", splitProviderPrompt(firstText(source.get("providerPrompt"), source.get("seedancePrompt"), source.get("visualPrompt"), source.get("action"), source.get("description")), index + 1, chunks.size(), chunk));
                result.add(capabilityScene(splitScene, result.size() + 1, chunk, index + 1, chunks.size(), safeMaxClipSeconds));
            }
        }
        return result;
    }

    private Map<String, Object> capabilityScene(
            Map<String, Object> source,
            int sceneNumber,
            String dialogueChunk,
            int dialoguePart,
            int dialoguePartCount,
            int maxClipSeconds
    ) {
        Map<String, Object> scene = new LinkedHashMap<>(source == null ? Map.of() : source);
        int durationSeconds = Math.max(1, Math.min(
                maxClipSeconds,
                Math.max(
                        positiveInt(firstValue(scene.get("durationSeconds"), scene.get("duration_seconds"), scene.get("duration")), maxClipSeconds),
                        estimatedDialogueSeconds(firstText(dialogueChunk, dialogueTextForScene(scene)))
                )
        ));
        scene.put("id", "scene-" + sceneNumber);
        scene.put("sceneId", "scene-" + sceneNumber);
        scene.put("sceneNumber", sceneNumber);
        scene.put("shotNumber", sceneNumber);
        scene.put("durationSeconds", durationSeconds);
        scene.put("maxClipSeconds", maxClipSeconds);
        scene.put("maxDialogueSecondsPerScene", Math.max(1, maxClipSeconds - 1));
        scene.put("dialogueTimingPolicy", "Complete dialogue for this scene part must fit within " + maxClipSeconds + " seconds without paraphrasing or dropping words.");
        if (dialoguePartCount > 1) {
            scene.put("dialoguePart", dialoguePart);
            scene.put("dialoguePartCount", dialoguePartCount);
            scene.put("storyboardSegmentation", "model_duration_capability");
        }
        return scene;
    }

    private List<String> dialogueChunks(String dialogue, int maxWordsPerPart) {
        String text = normalizeDialogueText(dialogue);
        if (text.isBlank()) {
            return List.of();
        }
        String[] words = text.split("\\s+");
        if (words.length <= maxWordsPerPart) {
            return List.of(text);
        }
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < words.length; index += maxWordsPerPart) {
            StringBuilder builder = new StringBuilder();
            int end = Math.min(words.length, index + maxWordsPerPart);
            for (int cursor = index; cursor < end; cursor++) {
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(words[cursor]);
            }
            chunks.add(builder.toString());
        }
        return chunks;
    }

    private String splitProviderPrompt(String basePrompt, int partNumber, int partCount, String dialogueChunk) {
        String prompt = firstText(basePrompt, "Continue the same storyboard beat with locked character, hair, wardrobe, background, lighting, and camera continuity.");
        return prompt
                + " Dialogue part "
                + partNumber
                + " of "
                + partCount
                + ": speak exactly \""
                + dialogueChunk
                + "\" in order, without paraphrasing or skipping any words.";
    }

    List<Map<String, Object>> enrichScenesWithStoryboardReferences(
            CreatorScript script,
            List<Map<String, Object>> scenes,
            Map<String, Object> request
    ) {
        if (script == null || scenes == null || scenes.isEmpty()) {
            return scenes == null ? List.of() : scenes;
        }

        // Video generation is the final layer of this workflow, not an independent pipeline -
        // it should inherit the same per-shot director/DP planning (storyboardTag/
        // lightingBuildSheetTag/cameraPlanSheetTag) that storyboard image generation already
        // uses, not reconstruct a shallower version of it from raw screenplay fields. This is a
        // separate lookup from the CreatorStoryboard/CreatorStoryboardScene block below - shot
        // plans exist independent of whether a storyboard record was ever created, so this must
        // not be gated by the storyboard-presence early return that block used to have.
        Map<Integer, CreatorScriptShotPlan> shotPlanByShot = new LinkedHashMap<>();
        for (CreatorScriptShotPlan plan : shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())) {
            if (plan.getShotNumber() != null && ProductionPlanTagService.DEFAULT_STYLE_KEY.equals(plan.getStyleKey())) {
                shotPlanByShot.putIfAbsent(plan.getShotNumber(), plan);
            }
        }

        CreatorStoryboard storyboard = storyboardForScript(script, request);
        Map<Integer, CreatorStoryboardScene> storyboardSceneByShot = new LinkedHashMap<>();
        Map<UUID, CreatorAsset> assetsById = new LinkedHashMap<>();
        if (storyboard != null && storyboard.getId() != null) {
            List<CreatorStoryboardScene> storyboardScenes = storyboardSceneRepository.findByStoryboardIdOrderByShotNumberAsc(storyboard.getId());
            List<UUID> assetIds = new ArrayList<>();
            for (CreatorStoryboardScene storyboardScene : storyboardScenes) {
                if (storyboardScene.getShotNumber() != null) {
                    storyboardSceneByShot.putIfAbsent(storyboardScene.getShotNumber(), storyboardScene);
                }
                if (storyboardScene.getImageAssetId() != null) {
                    assetIds.add(storyboardScene.getImageAssetId());
                }
                UUID productionImageAssetId = uuidValue(mapValue(storyboardScene.getMetadata()).get("productionImageAssetId"));
                if (productionImageAssetId != null) {
                    assetIds.add(productionImageAssetId);
                }
            }
            if (!assetIds.isEmpty()) {
                assetRepository.findAllById(assetIds).forEach(asset -> assetsById.put(asset.getId(), asset));
            }
        }
        if (storyboardSceneByShot.isEmpty() && shotPlanByShot.isEmpty()) {
            return scenes;
        }

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (int index = 0; index < scenes.size(); index++) {
            Map<String, Object> source = scenes.get(index);
            Map<String, Object> scene = new LinkedHashMap<>(source == null ? Map.of() : source);
            int shotNumber = positiveInt(firstValue(
                    scene.get("shotNumber"),
                    scene.get("shot_number"),
                    scene.get("sceneNumber"),
                    scene.get("scene_number")
            ), index + 1);
            CreatorStoryboardScene storyboardScene = storyboardSceneByShot.get(shotNumber);
            if (storyboard != null && storyboardScene != null) {
                attachStoryboardReference(storyboard, storyboardScene, assetsById.get(storyboardScene.getImageAssetId()), scene);
                UUID productionImageAssetId = uuidValue(mapValue(storyboardScene.getMetadata()).get("productionImageAssetId"));
                attachProductionImageAnchor(assetsById.get(productionImageAssetId), scene);
            }
            shotPlanTagGateway.attachTo(shotPlanByShot.get(shotNumber), scene);
            Map<String, Object> requestOverride = requestSceneOverride(request, scene, index + 1);
            if (!requestOverride.isEmpty()) {
                scene.putAll(requestOverride);
            }
            enriched.add(scene);
        }
        return enriched;
    }

    private CreatorStoryboard storyboardForScript(CreatorScript script, Map<String, Object> request) {
        String tenantId = defaultString(script.getTenantId(), "unknown");
        String userId = defaultString(script.getUserId(), "anonymous");
        UUID requestedStoryboardId = uuidValue(firstValue(
                request == null ? null : request.get("storyboardId"),
                request == null ? null : request.get("selectedStoryboardId"),
                request == null ? null : request.get("approvedStoryboardId")
        ));
        if (requestedStoryboardId != null) {
            CreatorStoryboard requested = storyboardRepository
                    .findByIdAndTenantIdAndUserId(requestedStoryboardId, tenantId, userId)
                    .orElse(null);
            if (requested != null) {
                return requested;
            }
        }
        if (script.getProjectId() != null && script.getStoryIdeaId() != null) {
            CreatorStoryboard storyboard = storyboardRepository
                    .findTopByProjectIdAndIdeaIdAndTenantIdAndUserIdOrderByCreatedAtDesc(
                            script.getProjectId(),
                            script.getStoryIdeaId(),
                            tenantId,
                            userId
                    )
                    .orElse(null);
            if (storyboard != null) {
                return storyboard;
            }
        }
        if (script.getStoryIdeaId() != null) {
            CreatorStoryboard storyboard = storyboardRepository
                    .findTopByIdeaIdAndTenantIdAndUserIdOrderByCreatedAtDesc(script.getStoryIdeaId(), tenantId, userId)
                    .orElse(null);
            if (storyboard != null) {
                return storyboard;
            }
        }
        return null;
    }

    private void attachStoryboardReference(
            CreatorStoryboard storyboard,
            CreatorStoryboardScene storyboardScene,
            CreatorAsset asset,
            Map<String, Object> scene
    ) {
        scene.putIfAbsent("storyboardId", storyboard.getId().toString());
        scene.putIfAbsent("storyboardSceneId", storyboardScene.getId() == null ? null : storyboardScene.getId().toString());
        scene.putIfAbsent("storyboardShotNumber", storyboardScene.getShotNumber());
        putIfBlank(scene, "storyboardTitle", storyboardScene.getTitle());
        putIfBlank(scene, "storyboardSketchPrompt", storyboardScene.getSketchPrompt());
        putIfBlank(scene, "shotType", storyboardScene.getShotType());
        putIfBlank(scene, "cameraMovement", storyboardScene.getCameraMovement());
        putIfBlank(scene, "lensSuggestion", storyboardScene.getLensSuggestion());
        putIfBlank(scene, "composition", storyboardScene.getComposition());
        putIfBlank(scene, "retentionGoal", storyboardScene.getRetentionGoal());
        if (asset == null) {
            return;
        }

        String imageUrl = storyboardImageUrl(asset);
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("assetId", asset.getId() == null ? null : asset.getId().toString());
        image.put("assetType", asset.getAssetType());
        image.put("bucket", asset.getBucket());
        image.put("objectKey", asset.getObjectKey());
        image.put("contentType", asset.getContentType());
        image.put("sizeBytes", asset.getSizeBytes());
        image.put("publicUrl", imageUrl);
        image.put("signedUrl", imageUrl);
        image.put("url", imageUrl);

        scene.put("storyboardImage", image);
        scene.put("storyboardAsset", image);
        putIfBlank(scene, "storyboardImageUrl", imageUrl);
        putIfBlank(scene, "storyboardUrl", imageUrl);
    }

    private String storyboardImageUrl(CreatorAsset asset) {
        if (asset == null) {
            return "";
        }
        String bucket = firstText(asset.getBucket());
        String objectKey = firstText(asset.getObjectKey());
        if (!bucket.isBlank() && !objectKey.isBlank()) {
            try {
                return assetStorageService.signedUrl(bucket, objectKey, SIGNED_URL_TTL);
            } catch (RuntimeException ex) {
                log.warn("Could not sign storyboard image assetId={} objectKey={} errorType={} errorMessage={}",
                        asset.getId(), objectKey, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        return firstText(asset.getPublicUrl());
    }

    private void attachProductionImageAnchor(CreatorAsset asset, Map<String, Object> scene) {
        if (asset == null || scene == null) {
            return;
        }
        String imageUrl = storyboardImageUrl(asset);
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("assetId", asset.getId() == null ? null : asset.getId().toString());
        image.put("assetType", asset.getAssetType());
        image.put("referenceRole", "generated_product_scene_frame");
        image.put("assetRole", "generated_product_scene_frame");
        image.put("bucket", asset.getBucket());
        image.put("objectKey", asset.getObjectKey());
        image.put("contentType", asset.getContentType());
        image.put("sizeBytes", asset.getSizeBytes());
        image.put("publicUrl", imageUrl);
        image.put("signedUrl", imageUrl);
        image.put("url", imageUrl);
        scene.put("productionImage", image);
        scene.put("productImageAsset", image);
        scene.put("generatedProductImageAssets", List.of(image));
        putIfBlank(scene, "productionImageUrl", imageUrl);
        putIfBlank(scene, "generatedProductImageUrl", imageUrl);
        putIfBlank(scene, "imageAnchorUrl", imageUrl);
        putIfBlank(scene, "productImageUrl", imageUrl);
    }

    CreatorScript loadScript(UUID scriptId, String tenantId, String userId) {
        if (scriptId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Script id is required.");
        }
        return scriptRepository
                .findByIdAndTenantIdAndUserId(scriptId, defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator script was not found."));
    }

    RunRecord loadRun(UUID runId, String tenantId, String userId) {
        if (runId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video run id is required.");
        }
        long databaseLookupStartedNanos = System.nanoTime();
        CreatorGenerationJob job = generationJobRepository
                .findLatestScreenplayVideoRunJob(defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), runId.toString())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay video run was not found."));
        long databaseLookupMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - databaseLookupStartedNanos);
        log.info("Screenplay video run database lookup runId={} jobId={} status={} databaseLookupMs={}",
                runId, job.getId(), job.getStatus(), databaseLookupMs);
        Map<String, Object> output = copyMap(job.getOutputPayload());
        Map<String, Object> run = firstNonEmptyMap(output.get("videoRun"), output.get("screenplayVideoRun"), output);
        if (run.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay video run payload was not found.");
        }
        videoRunHydrator().mergeLatestAudioState(run, defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), runId.toString());
        UUID scriptId = uuidValue(run.get("scriptId"));
        if (scriptId != null) {
            CreatorScript script = loadScript(scriptId, tenantId, userId);
            List<Map<String, Object>> scenes = reconcilePreparedRunScenes(script, run, mapListValue(run.get("scenes")));
            run.put("scenes", scenes);
            videoRunHydrator().invalidateStaleCombinedDialogueAudio(run, scenes);
            run.put("sceneClips", scenes);
            Map<String, Object> timeline = copyMap(run.get("timeline"));
            if (!timeline.isEmpty()) {
                timeline.put("sceneCount", scenes.size());
                run.put("timeline", timeline);
            }
        }
        videoRunHydrator().refreshRunMediaUrls(run);
        return new RunRecord(job, run);
    }

    Map<String, Object> hydrateVideoRunForResponse(Map<String, Object> sourceRun, String tenantId, String userId) {
        Map<String, Object> run = copyMap(sourceRun);
        UUID scriptId = uuidValue(run.get("scriptId"));
        if (scriptId != null) {
            CreatorScript script = loadScript(scriptId, tenantId, userId);
            List<Map<String, Object>> scenes = reconcilePreparedRunScenes(script, run, mapListValue(run.get("scenes")));
            scenes = enrichScenesWithStoryboardReferences(script, scenes, Map.of());
            run.put("scenes", scenes);
            videoRunHydrator().invalidateStaleCombinedDialogueAudio(run, scenes);
            run.put("sceneClips", scenes);
            Map<String, Object> timeline = copyMap(run.get("timeline"));
            if (!timeline.isEmpty()) {
                timeline.put("sceneCount", scenes.size());
                run.put("timeline", timeline);
            }
        }
        videoRunHydrator().refreshRunMediaUrls(run);
        return run;
    }

    private VideoRunHydrator videoRunHydrator() {
        return new VideoRunHydrator(this, generationJobRepository, assetStorageService);
    }

    void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (target == null || source == null || key == null || !source.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    List<Map<String, Object>> sceneDialogueAudioInputs(List<Map<String, Object>> scenes) {
        List<Map<String, Object>> inputs = new ArrayList<>();
        if (scenes == null) {
            return inputs;
        }
        for (int index = 0; index < scenes.size(); index++) {
            Map<String, Object> scene = scenes.get(index);
            Map<String, Object> asset = firstMap(scene.get("dialogueAudio"));
            String bucket = firstText(asset.get("bucket"));
            String objectKey = firstText(asset.get("objectKey"));
            if (bucket.isBlank() || objectKey.isBlank()) {
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("sceneId", firstText(scene.get("id"), scene.get("sceneId"), "scene-" + (index + 1)));
            input.put("sceneNumber", intValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), index + 1));
            input.put("bucket", bucket);
            input.put("objectKey", objectKey);
            input.put("contentType", firstText(asset.get("contentType"), "audio/mpeg"));
            inputs.add(input);
        }
        return inputs;
    }

    List<Integer> missingSceneDialogueAudioNumbers(List<Map<String, Object>> scenes) {
        List<Integer> missing = new ArrayList<>();
        if (scenes == null) {
            return missing;
        }
        for (int index = 0; index < scenes.size(); index++) {
            Map<String, Object> scene = scenes.get(index);
            Map<String, Object> asset = firstMap(scene.get("dialogueAudio"));
            if (!hasStoredAssetLocation(asset)) {
                missing.add(intValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), index + 1));
            }
        }
        return missing;
    }

    String sceneDialogueAudioFingerprint(List<Map<String, Object>> inputs, int totalSceneCount) {
        if (inputs == null || inputs.isEmpty()) {
            return "";
        }
        StringBuilder material = new StringBuilder();
        for (Map<String, Object> input : inputs) {
            material.append(firstText(input.get("sceneId")))
                    .append(":")
                    .append(intValue(input.get("sceneNumber"), 0))
                    .append(":")
                    .append(firstText(input.get("bucket")))
                    .append("/")
                    .append(firstText(input.get("objectKey")))
                    .append("|");
        }
        return stableFingerprint(
                "scene-dialogue-combined-v1",
                String.valueOf(totalSceneCount),
                material.toString()
        );
    }

    void clearCombinedDialogueAudio(Map<String, Object> run) {
        if (run == null) {
            return;
        }
        run.remove("combinedDialogueAudio");
        run.remove("combinedSceneDialogueAudio");
        run.remove("combinedDialogueTrack");
        run.remove("combinedDialogueSummary");
    }

    Map<String, Object> refreshAudioAssetReference(Object value) {
        Map<String, Object> asset = copyMap(value);
        if (asset.isEmpty()) {
            return asset;
        }
        String bucket = firstText(asset.get("bucket"));
        String objectKey = firstText(asset.get("objectKey"));
        if (bucket.isBlank() || objectKey.isBlank()) {
            return asset;
        }
        try {
            String signedUrl = assetStorageService.signedUrl(bucket, objectKey, SIGNED_URL_TTL);
            asset.put("assetUrl", signedUrl);
            asset.put("signedUrl", signedUrl);
            asset.put("publicUrl", signedUrl);
            asset.put("storageProvider", "minio");
            asset.put("storageStatus", "SAVED_TO_MINIO");
            asset.put("signedUrlTtlSeconds", SIGNED_URL_TTL.toSeconds());
            asset.put("signedUrlRefreshedAt", OffsetDateTime.now().toString());
            asset.put("mediaAccessPolicy", "renew_on_authenticated_video_run_fetch");
            asset.put("mediaUrlRefreshAfterSeconds", MEDIA_URL_RENEWAL_SECONDS);
        } catch (RuntimeException ex) {
            log.warn("Could not refresh screenplay audio signed URL bucket={} objectKey={} errorType={} errorMessage={}",
                    bucket, objectKey, ex.getClass().getSimpleName(), ex.getMessage());
        }
        return asset;
    }

    int findSceneIndex(List<Map<String, Object>> scenes, String sceneId) {
        if (scenes == null || scenes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No scenes were found in this video run.");
        }
        String target = defaultString(sceneId, "");
        for (int index = 0; index < scenes.size(); index++) {
            ScreenplaySceneView scene = SceneViewMapper.sceneView(scenes.get(index), objectMapper);
            String id = defaultString(scene.id(), "scene-" + (index + 1));
            if (target.equals(id) || target.equals(String.valueOf(scene.sceneNumber())) || target.equals(String.valueOf(scene.shotNumber()))) {
                return index;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scene was not found in this video run.");
    }

    Map<String, Object> outputPayload(Map<String, Object> run, String message) {
        Map<String, Object> safeRun = sanitizeProviderStorageMap(run);
        if (safeRun.get("scenes") != null && safeRun.get("scenes").equals(safeRun.get("sceneClips"))) {
            safeRun.remove("sceneClips");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        copyIfPresent(output, safeRun, "runId");
        copyIfPresent(output, safeRun, "jobId");
        copyIfPresent(output, safeRun, "scriptId");
        copyIfPresent(output, safeRun, "projectId");
        copyIfPresent(output, safeRun, "tenantId");
        copyIfPresent(output, safeRun, "userId");
        copyIfPresent(output, safeRun, "status");
        output.put("videoRun", safeRun);
        output.put("message", message);
        return output;
    }

    private Map<String, Object> sanitizeProviderStorageMap(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(sanitizeProviderStoragePayload(payload, ""), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private Object sanitizeProviderStoragePayload(Object value, String key) {
        String normalizedKey = defaultString(key, "").toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (normalizedKey.contains("authorization")
                || normalizedKey.contains("apikey")
                || normalizedKey.contains("secret")
                || normalizedKey.contains("token")) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            rawMap.forEach((rawKey, rawValue) -> {
                String childKey = stringValue(rawKey, "");
                sanitized.put(childKey, sanitizeProviderStoragePayload(rawValue, childKey));
            });
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : collection) {
                sanitized.add(sanitizeProviderStoragePayload(item, key));
            }
            return sanitized;
        }
        if (value instanceof byte[] bytes) {
            return "[binary bytes=" + bytes.length + " stored_in_object_storage]";
        }
        if (value instanceof String text) {
            if (isBase64StoragePayloadKey(normalizedKey) && text.length() > 80) {
                return "[base64 chars=" + text.length() + " omitted_stored_in_object_storage]";
            }
            int dataUrlMarker = text.indexOf(";base64,");
            if (dataUrlMarker > 0 && text.length() > dataUrlMarker + 80) {
                return "[data-url chars=" + text.length() + " omitted_stored_in_object_storage]";
            }
            if (text.length() > 20000 && looksLikeBase64(text)) {
                return "[base64-like chars=" + text.length() + " omitted_stored_in_object_storage]";
            }
            if (text.length() > 40000) {
                return truncate(text, 40000);
            }
        }
        return value;
    }

    private boolean isBase64StoragePayloadKey(String normalizedKey) {
        return normalizedKey.equals("data")
                || normalizedKey.equals("audiocontent")
                || normalizedKey.equals("audio")
                || normalizedKey.equals("imagebytes")
                || normalizedKey.equals("bytesbase64encoded")
                || normalizedKey.equals("b64json")
                || normalizedKey.equals("base64")
                || normalizedKey.endsWith("base64");
    }

    private boolean looksLikeBase64(String value) {
        if (value == null || value.length() < 512) {
            return false;
        }
        int checked = 0;
        int valid = 0;
        int max = Math.min(value.length(), 4096);
        for (int index = 0; index < max; index++) {
            char ch = value.charAt(index);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            checked++;
            if ((ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '+'
                    || ch == '/'
                    || ch == '=') {
                valid++;
            }
        }
        return checked > 0 && valid >= Math.max(1, checked * 98 / 100);
    }

    String generationModeFor(Map<String, Object> scene, Map<String, Object> runOrRequest, Map<String, Object> request) {
        String productionStyle = normalizeProductionStyle(firstText(
                request == null ? null : request.get("productionStyle"),
                runOrRequest == null ? null : runOrRequest.get("productionStyle")
        ));
        if ("full_ai".equals(productionStyle)) {
            return "ai_generated";
        }
        String hybridSceneMode = normalizeFounderHybridSceneMode(firstText(
                request == null ? null : request.get("hybridSceneMode"),
                runOrRequest == null ? null : runOrRequest.get("hybridSceneMode")
        ));
        boolean founderLedHybridEnabled = booleanValue(firstValue(
                request == null ? null : request.get("founderLedHybridEnabled"),
                runOrRequest == null ? null : runOrRequest.get("founderLedHybridEnabled")
        ), false);
        if (founderLedHybridEnabled && "full_founder".equals(hybridSceneMode)) {
            return "talking_head";
        }
        String override = overrideForScene(scene, request == null ? Map.of() : mapValue(request.get("sceneModeOverrides")));
        if (!override.isBlank()) {
            return normalizeGenerationMode(override);
        }
        ScreenplaySceneView sceneView = SceneViewMapper.sceneView(scene, objectMapper);
        return normalizeGenerationMode(firstText(
                request == null ? null : request.get("generationMode"),
                sceneView.generationMode(),
                sceneView.generationModeSnakeCase(),
                sceneView.assetCaptureMode(),
                sceneView.assetCaptureModeSnakeCase(),
                "ai_generated"
        ));
    }

    boolean shouldAutoAssignFounderAvatarScene(
            List<Map<String, Object>> scenes,
            Map<String, Object> scene,
            int sceneIndex,
            Map<String, Object> request,
            Map<String, Object> founderAvatarProfile,
            String productionStyle,
            String hybridSceneMode,
            boolean founderLedHybridEnabled
    ) {
        if (!founderLedHybridEnabled
                || founderAvatarProfile == null
                || founderAvatarProfile.isEmpty()
                || "full_ai".equals(productionStyle)) {
            return false;
        }
        String mode = normalizeFounderHybridSceneMode(hybridSceneMode);
        if ("full_founder".equals(mode)) {
            return true;
        }
        if (hasExplicitGenerationMode(scene, request)) {
            return false;
        }
        String override = overrideForScene(scene, request == null ? Map.of() : mapValue(request.get("sceneModeOverrides")));
        if (!override.isBlank()) {
            return false;
        }
        if ("ai_only".equals(mode)) {
            return false;
        }
        if (dialogueTextForScene(scene).isBlank()) {
            return false;
        }
        if ("talking_head".equals(mode)) {
            return true;
        }
        List<Integer> speakingIndexes = founderSpeakingSceneIndexes(scenes);
        if (speakingIndexes.isEmpty()) {
            return false;
        }
        int first = speakingIndexes.get(0);
        int middle = speakingIndexes.get(speakingIndexes.size() / 2);
        int last = speakingIndexes.get(speakingIndexes.size() - 1);
        return sceneIndex == first || sceneIndex == middle || sceneIndex == last;
    }

    private List<Integer> founderSpeakingSceneIndexes(List<Map<String, Object>> scenes) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < scenes.size(); index++) {
            Map<String, Object> scene = scenes.get(index);
            if (!dialogueTextForScene(scene).isBlank()) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private boolean hasExplicitGenerationMode(Map<String, Object> scene, Map<String, Object> request) {
        return !firstText(
                request == null ? null : request.get("generationMode"),
                scene == null ? null : scene.get("generationMode"),
                scene == null ? null : scene.get("generation_mode"),
                scene == null ? null : scene.get("assetCaptureMode"),
                scene == null ? null : scene.get("asset_capture_mode")
        ).isBlank();
    }

    String normalizeFounderHybridSceneMode(String value) {
        String normalized = defaultString(value, "hybrid")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .trim();
        if (normalized.equals("ai_only") || normalized.equals("full_ai") || normalized.equals("broll_only")) {
            return "ai_only";
        }
        if (normalized.equals("full_founder")
                || normalized.equals("all_founder")) {
            return "full_founder";
        }
        if (normalized.equals("talking_head")
                || normalized.equals("avatar")
                || normalized.equals("avatar_only")
                || normalized.equals("founder_only")
                || normalized.equals("talking_head_only")) {
            return "full_founder";
        }
        return "hybrid";
    }

    private String overrideForScene(Map<String, Object> scene, Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return "";
        }
        String id = firstText(scene.get("id"), scene.get("sceneId"), scene.get("scene_id"));
        String sceneNumber = stringValue(scene.get("sceneNumber"), "");
        String shotNumber = stringValue(scene.get("shotNumber"), "");
        return firstText(overrides.get(id), overrides.get(sceneNumber), overrides.get(shotNumber), overrides.get("scene-" + sceneNumber));
    }

    String providerPromptFor(Map<String, Object> scene, Map<String, Object> scriptPayload, Map<String, Object> request) {
        String providerPrompt = firstText(
                scene.get("providerPrompt"),
                scene.get("provider_prompt"),
                scene.get("seedancePrompt"),
                scene.get("seedance_prompt"),
                scene.get("assetGenerationPrompt"),
                scene.get("sketchPrompt"),
                scene.get("visualPrompt"),
                scene.get("action"),
                scene.get("description")
        );
        String hook = firstText(scene.get("hook"), scene.get("openingHook"), scene.get("hookLine"));
        String retentionGoal = firstText(scene.get("retentionGoal"), scene.get("retention_goal"));
        String patternInterrupt = firstText(scene.get("patternInterrupt"), scene.get("pattern_interrupt"));
        if (!providerPrompt.isBlank()) {
            String commercialDirection = "";
            if (!hook.isBlank()) commercialDirection += "\nOpening hook: " + hook + ".";
            if (!retentionGoal.isBlank()) commercialDirection += "\nRetention goal: " + retentionGoal + ".";
            if (!patternInterrupt.isBlank()) commercialDirection += "\nPattern interrupt: " + patternInterrupt + ".";
            return (providerPrompt + commercialDirection).trim();
        }
        String globalPrompt = firstText(
                mapValue(scriptPayload.get("seedancePromptStrategy")).get("globalConsistencyPrompt"),
                mapValue(request.get("seedancePromptStrategy")).get("globalConsistencyPrompt")
        );
        return ("%s\nScene: %s\nAction: %s\nOpening hook: %s\nRetention goal: %s\nPattern interrupt: %s").formatted(
                globalPrompt,
                firstText(scene.get("title"), scene.get("beatTitle"), "Scene"),
                firstText(scene.get("action"), scene.get("description"), "Generate a cinematic creator video scene."),
                hook,
                retentionGoal,
                patternInterrupt
        ).trim();
    }

    Map<String, Object> compactScene(Map<String, Object> scene) {
        if (scene == null || scene.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        List.of(
                "id",
                "sceneId",
                "sceneNumber",
                "shotNumber",
                "title",
                "durationSeconds",
                "startTime",
                "endTime",
                "generationMode",
                "providerPrompt",
                "prompt",
                "videoPrompt",
                "animationPrompt",
                "videoMotionPrompt",
                "imagePrompt",
                "storyboardImagePrompt",
                "cameraMovement",
                "negativePrompt",
                "noHumans",
                "seedancePrompt",
                "action",
                "description",
                "dialogue",
                "voiceover",
                "caption",
                "captionText",
                "hook",
                "openingHook",
                "hookLine",
                "retentionGoal",
                "patternInterrupt",
                "sceneDetail",
                "sceneDetails",
                "background",
                "backgroundDetail",
                "setting",
                "location",
                "environment",
                "setDescription",
                "character",
                "characters",
                "characterDetail",
                "characterDetails",
                "wardrobe",
                "props",
                "lighting",
                "camera",
                "shotType",
                "visualStyle",
                "brollStyle",
                "captionStyle",
                "adFormat",
                "adFormatKey",
                "formatStructure",
                "formatHookStyle",
                "formatRetentionStyle",
                "retentionGoal",
                "patternInterrupt"
        ).forEach(key -> {
            if (scene.containsKey(key)) {
                compact.put(key, scene.get(key));
            }
        });
        return compact;
    }

    List<Map<String, Object>> srtCuesForScene(List<Object> cues, Map<String, Object> scene) {
        if (cues == null || cues.isEmpty()) {
            return List.of();
        }
        int start = intValue(scene.get("startSeconds"), 0);
        int end = intValue(scene.get("endSeconds"), start + positiveInt(scene.get("durationSeconds"), 15));
        return cues.stream()
                .map(MapCoercion::mapValue)
                .filter(cue -> cue.isEmpty()
                        || overlaps(start, end, intValue(firstValue(cue.get("startSeconds"), cue.get("start")), start), intValue(firstValue(cue.get("endSeconds"), cue.get("end")), end)))
                .limit(6)
                .toList();
    }

    private List<Map<String, Object>> fallbackDialogueCuesForScene(Map<String, Object> scene) {
        String dialogue = dialogueTextForScene(scene);
        if (dialogue.isBlank()) {
            return List.of();
        }
        int start = intValue(scene == null ? null : scene.get("startSeconds"), 0);
        int duration = positiveInt(scene == null ? null : scene.get("durationSeconds"), Math.max(1, estimatedDialogueSeconds(dialogue)));
        int end = intValue(scene == null ? null : scene.get("endSeconds"), start + duration);
        if (end <= start) {
            end = start + duration;
        }
        Map<String, Object> cue = new LinkedHashMap<>();
        cue.put("startSeconds", start);
        cue.put("endSeconds", end);
        cue.put("text", dialogue);
        cue.put("source", "scene_dialogue_fallback");
        cue.put("coverageRequired", true);
        return List.of(cue);
    }

    String dialogueTextForScene(Map<String, Object> scene) {
        if (scene == null || scene.isEmpty()) {
            return "";
        }
        ScreenplaySceneView view = SceneViewMapper.sceneView(scene, objectMapper);
        String explicit = firstText(
                view.dialogueScript(),
                view.exactDialogue(),
                view.spokenDialogue(),
                view.voiceover(),
                view.voiceOver(),
                view.narration(),
                view.spokenLine()
        );
        if (!explicit.isBlank()) {
            return normalizeDialogueText(explicit);
        }
        String structured = dialogueObjectText(view.dialogue());
        if (!structured.isBlank()) {
            return normalizeDialogueText(structured);
        }
        return normalizeDialogueText(firstText(view.caption(), view.captionText(), view.textOverlay()));
    }

    private String dialogueObjectText(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(this::dialogueObjectText)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = mapValue(rawMap);
            String direct = firstText(
                    map.get("text"),
                    map.get("line"),
                    map.get("voiceover"),
                    map.get("voiceOver"),
                    map.get("narration"),
                    map.get("caption")
            );
            if (!direct.isBlank()) {
                return direct;
            }
            return map.values().stream()
                    .map(this::dialogueObjectText)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        return firstText(value);
    }

    public static String normalizeDialogueText(String value) {
        return defaultString(value, "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    int estimatedDialogueSeconds(Map<String, Object> scene) {
        return estimatedDialogueSeconds(dialogueTextForScene(scene));
    }

    int estimatedDialogueSeconds(String dialogue) {
        String text = normalizeDialogueText(dialogue);
        if (text.isBlank()) {
            return 0;
        }
        int words = text.split("\\s+").length;
        return Math.max(1, (int) Math.ceil(words / 2.4d) + 1);
    }

    private boolean overlaps(int startA, int endA, int startB, int endB) {
        return Math.max(startA, startB) < Math.min(endA, endB);
    }

    String sceneIdFor(Map<String, Object> scene, int sceneNumber) {
        String id = firstText(scene.get("id"), scene.get("sceneId"), scene.get("scene_id"), scene.get("shotId"), scene.get("shot_id"));
        if (!id.isBlank()) {
            return id;
        }
        return "scene-" + sceneNumber;
    }

    Map<String, Object> rateLimitPolicy(String provider) {
        return videoProviderCatalog().rateLimitPolicy(provider);
    }

    Map<String, Object> imageLedAdPlan(
            Map<String, Object> request,
            Map<String, Object> contextPayload,
            Map<String, Object> videoFinishingPlan
    ) {
        Map<String, Object> plan = new LinkedHashMap<>(firstMap(
                request == null ? null : request.get("imageLedAdPlan"),
                contextPayload == null ? null : contextPayload.get("imageLedAdPlan"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("imageLedAdPlan")
        ));
        boolean enabled = booleanValue(firstValue(
                plan.get("enabled"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("imageLedAdMode"),
                request == null ? null : request.get("imageLedAdMode")
        ), false);
        plan.put("enabled", enabled);
        plan.put("referenceImageMode", firstText(
                plan.get("referenceImageMode"),
                request == null ? null : request.get("referenceImageMode"),
                contextPayload == null ? null : contextPayload.get("referenceImageMode"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("referenceImageMode"),
                enabled ? "product_motion_anchor" : "prompt_only"
        ));
        plan.put("useStoryboardReferences", booleanValue(firstValue(
                plan.get("useStoryboardReferences"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("useStoryboardReferences")
        ), true));
        plan.put("requireImageAnchors", booleanValue(firstValue(
                plan.get("requireImageAnchors"),
                request == null ? null : request.get("requireImageAnchors"),
                contextPayload == null ? null : contextPayload.get("requireImageAnchors"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("requireImageAnchors")
        ), false));
        plan.put("productMotionPrompt", firstText(
                plan.get("productMotionPrompt"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("productMotionPrompt")
        ));
        plan.put("providerPolicy", firstText(
                plan.get("providerPolicy"),
                enabled ? "generate_or_use_approved_product_image_anchors_then_render_video_from_image" : "storyboard_guidance_in_prompt_only"
        ));
        if (!plan.containsKey("sceneUseCases")) {
            plan.put("sceneUseCases", List.of(
                    "d2c_product_packshot",
                    "slow_motion_food_or_texture",
                    "macro_product_detail",
                    "multi_direction_product_motion"
            ));
        }
        return plan;
    }

    Map<String, Object> audioProductionPlan(
            Map<String, Object> request,
            Map<String, Object> contextPayload,
            Map<String, Object> soundDesignPlan,
            Map<String, Object> videoFinishingPlan
    ) {
        Map<String, Object> plan = new LinkedHashMap<>(firstMap(
                request == null ? null : request.get("audioProductionPlan"),
                contextPayload == null ? null : contextPayload.get("audioProductionPlan"),
                soundDesignPlan == null ? null : soundDesignPlan.get("audioProductionPlan")
        ));
        plan.putIfAbsent("voiceDialogue", Map.of(
                "prompt", firstText(
                        videoFinishingPlan == null ? null : videoFinishingPlan.get("voiceDialoguePrompt"),
                        mapValue(soundDesignPlan == null ? null : soundDesignPlan.get("voiceDialogue")).get("prompt")
                ),
                "mode", firstText(videoFinishingPlan == null ? null : videoFinishingPlan.get("voiceMixMode"), "balanced"),
                "policy", "native_voice_or_dialogue_when_provider_supports_audio_then_final_mix"
        ));
        plan.putIfAbsent("music", firstNonNull(
                soundDesignPlan == null ? null : soundDesignPlan.get("backgroundMusic"),
                Map.of(
                        "mode", firstText(videoFinishingPlan == null ? null : videoFinishingPlan.get("backgroundMusicMode"), "auto"),
                        "prompt", firstText(videoFinishingPlan == null ? null : videoFinishingPlan.get("backgroundMusicPrompt")),
                        "duckUnderSpeech", true
                )
        ));
        plan.putIfAbsent("ambience", firstNonNull(
                soundDesignPlan == null ? null : soundDesignPlan.get("ambience"),
                Map.of("prompt", firstText(videoFinishingPlan == null ? null : videoFinishingPlan.get("ambiencePrompt")), "roomTone", "scene_matched_low_bed")
        ));
        plan.putIfAbsent("soundEffects", firstNonNull(
                soundDesignPlan == null ? null : soundDesignPlan.get("soundEffects"),
                Map.of("prompt", firstText(videoFinishingPlan == null ? null : videoFinishingPlan.get("soundFxPrompt")), "policy", "small_whooshes_clicks_transitions_sparingly")
        ));
        plan.put("audioMixStandards", audioMixStandards(
                plan.get("audioMixStandards"),
                soundDesignPlan == null ? null : soundDesignPlan.get("audioMixStandards"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("audioMixStandards")
        ));
        return plan;
    }

    Map<String, Object> editingPlan(
            Map<String, Object> request,
            Map<String, Object> contextPayload,
            Map<String, Object> videoFinishingPlan,
            Map<String, Object> soundDesignPlan,
            Map<String, Object> imageLedAdPlan,
            Map<String, Object> audioProductionPlan
    ) {
        Map<String, Object> plan = new LinkedHashMap<>(firstMap(
                request == null ? null : request.get("editorHandoffPlan"),
                contextPayload == null ? null : contextPayload.get("editorHandoffPlan"),
                request == null ? null : request.get("editingPlan"),
                contextPayload == null ? null : contextPayload.get("editingPlan"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("editorHandoffPlan"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("editingPlan")
        ));
        plan.put("editorNotes", firstText(
                plan.get("editorNotes"),
                videoFinishingPlan == null ? null : videoFinishingPlan.get("editingPlanPrompt"),
                "Tighten pacing, balance dialogue/music/SFX, verify captions, preserve approved shot order, and return an edited master."
        ));
        Object tools = firstValue(
                plan.get("recommendedTools"),
                plan.get("toolsToUse"),
                request == null ? null : request.get("recommendedEditingTools"),
                contextPayload == null ? null : contextPayload.get("recommendedEditingTools")
        );
        plan.put("recommendedTools", tools == null ? recommendedEditingTools() : tools);
        plan.put("toolsToUse", firstValue(plan.get("toolsToUse"), plan.get("recommendedTools")));
        plan.putIfAbsent("toolPolicy", "Use any equivalent professional editing stack, but include source music license, project settings, and export settings in delivery notes.");
        plan.put("imageLedAdPlan", imageLedAdPlan);
        plan.put("audioProductionPlan", audioProductionPlan);
        plan.put("soundDesignPlan", soundDesignPlan);
        plan.putIfAbsent("sourceAssetsRequired", List.of(
                "final merged clip",
                "accepted scene clips",
                "storyboard or product image anchors",
                "voice/dialogue script",
                "captions SRT",
                "music track or free-music license note",
                "sound design notes"
        ));
        plan.putIfAbsent("deliverables", List.of(
                "final_master_video",
                "verified_captions_srt",
                "balanced_voice_music_sfx_mix",
                "music_license_note",
                "edit_decision_notes"
        ));
        plan.putIfAbsent("qualityChecklist", List.of(
                "Final cut follows the approved shot order unless notes explain the change.",
                "Dialogue is speech-first and consistent.",
                "Background music is ducked under speech.",
                "Ambient room tone is present and not distracting.",
                "Small SFX are used sparingly.",
                "Reverb matches the visual scene.",
                "Captions match final audio and remain mobile-safe."
        ));
        plan.putIfAbsent("reviewPolicy", "two_included_revision_rounds_then_paid_changes");
        return plan;
    }

    private List<Map<String, Object>> recommendedEditingTools() {
        return List.of(
                Map.of("stage", "timeline_edit", "preferred", "DaVinci Resolve or Adobe Premiere Pro", "alternatives", List.of("Final Cut Pro", "CapCut Desktop")),
                Map.of("stage", "audio_mix", "preferred", "DaVinci Fairlight or Adobe Audition", "alternatives", List.of("Audacity", "Premiere Essential Sound")),
                Map.of("stage", "captions", "preferred", "Premiere captions or CapCut captions", "alternatives", List.of("Subtitle Edit", "DaVinci Resolve captions")),
                Map.of("stage", "color_finish", "preferred", "DaVinci Resolve", "alternatives", List.of("Premiere Lumetri", "Final Cut color board")),
                Map.of("stage", "free_music_source", "preferred", "YouTube Audio Library or Pixabay Music", "alternatives", List.of("Free Music Archive", "creator-provided licensed track"))
        );
    }

    List<String> referenceImageUrlsForScene(
            Map<String, Object> scene,
            Map<String, Object> request,
            Map<String, Object> contextPayload
    ) {
        return referenceImageResolver().referenceImageUrlsForScene(scene, request, contextPayload);
    }

    List<String> productReferenceImageUrls(
            Map<String, Object> request,
            Map<String, Object> scriptPayload,
            Map<String, Object> creatorContext
    ) {
        return referenceImageResolver().productReferenceImageUrls(request, scriptPayload, creatorContext);
    }

    List<Map<String, Object>> productReferenceImageAssets(
            Map<String, Object> request,
            Map<String, Object> scriptPayload,
            Map<String, Object> creatorContext
    ) {
        return referenceImageResolver().productReferenceImageAssets(request, scriptPayload, creatorContext);
    }

    Map<String, Object> enrichedVideoConsistencyBible(
            Map<String, Object> scene,
            Map<String, Object> request,
            Map<String, Object> contextPayload
    ) {
        return referenceImageResolver().enrichedVideoConsistencyBible(scene, request, contextPayload);
    }

    List<Map<String, Object>> generatedProductSceneImageAssets(
            Map<String, Object> scene,
            Map<String, Object> request
    ) {
        return referenceImageResolver().generatedProductSceneImageAssets(scene, request);
    }

    List<Map<String, Object>> canonicalProductImageAssets(
            Map<String, Object> request,
            Map<String, Object> contextPayload,
            List<Map<String, Object>> allProductAssets
    ) {
        return referenceImageResolver().canonicalProductImageAssets(request, contextPayload, allProductAssets);
    }

    List<String> generatedProductSceneImageUrls(
            Map<String, Object> scene,
            Map<String, Object> request,
            List<Map<String, Object>> generatedAssets
    ) {
        return referenceImageResolver().generatedProductSceneImageUrls(scene, request, generatedAssets);
    }

    List<String> canonicalProductImageUrls(
            Map<String, Object> request,
            Map<String, Object> contextPayload,
            List<Map<String, Object>> canonicalAssets
    ) {
        return referenceImageResolver().canonicalProductImageUrls(request, contextPayload, canonicalAssets);
    }

    List<Map<String, Object>> productImageAssetsForScene(
            Map<String, Object> scene,
            Map<String, Object> request,
            Map<String, Object> contextPayload
    ) {
        return referenceImageResolver().productImageAssetsForScene(scene, request, contextPayload);
    }

    void addProductImageAssets(List<Map<String, Object>> target, Object value) {
        referenceImageResolver().addProductImageAssets(target, value);
    }

    void addReferenceImageUrl(List<String> urls, Object value) {
        referenceImageResolver().addReferenceImageUrl(urls, value);
    }

    private ReferenceImageResolver referenceImageResolver() {
        return new ReferenceImageResolver();
    }

    Map<String, Object> withAudioMixStandards(Map<String, Object> plan) {
        Map<String, Object> normalized = new LinkedHashMap<>(plan == null ? Map.of() : plan);
        normalized.put("audioMixStandards", audioMixStandards(normalized.get("audioMixStandards"), normalized.get("audio_mix_standards")));
        normalized.put("audioProductionPolicy", "dialogue_first_music_ducked_room_tone_sparse_sfx_scene_reverb_smooth_fades");
        return normalized;
    }

    Map<String, Object> audioMixStandards(Object... overrides) {
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("dialogueLevel", "consistent_speech_first");
        standards.put("backgroundMusicDucking", "duck_under_speech");
        standards.put("ambientRoomTone", "maintain_low_scene_matched_room_tone");
        standards.put("soundEffectsUse", "small_sfx_sparingly_for_whooshes_clicks_transitions");
        standards.put("reverbMatch", "match_scene_space_and_camera_distance");
        standards.put("fades", "smooth_fades_between_audio_segments");
        standards.put("dialogueTargetDb", -3);
        standards.put("musicBedDb", -18);
        standards.put("ambienceBedDb", -22);
        standards.put("sfxPeakDb", -9);
        standards.put("fadeMs", 120);
        if (overrides != null) {
            for (Object override : overrides) {
                Map<String, Object> custom = mapValue(override);
                if (!custom.isEmpty()) {
                    standards.putAll(custom);
                }
            }
        }
        return standards;
    }

    List<Map<String, Object>> providerOptions() {
        return videoProviderCatalog().providerOptions();
    }

    List<Map<String, Object>> modelOptions(String provider) {
        return videoProviderCatalog().modelOptions(provider);
    }

    private String normalizeAvatarProviderMode(String value) {
        String normalized = defaultString(value, "synthesia")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .trim();
        if (normalized.equals("dalai_llama")
                || normalized.equals("dallai_llama")
                || normalized.equals("local")
                || normalized.equals("local_open_source")
                || normalized.equals("open_source")
                || normalized.equals("opensource")) {
            return "dalai_llama";
        }
        return "synthesia";
    }

    String avatarProviderFrom(Object... values) {
        for (Object value : values) {
            String text = firstText(value);
            if (!text.isBlank()) {
                return normalizeAvatarProviderMode(text);
            }
        }
        return "synthesia";
    }

    String avatarVoiceProvider(Map<String, Object> profile) {
        String provider = avatarProviderFrom(
                profile == null ? null : profile.get("avatarProviderMode"),
                profile == null ? null : profile.get("providerMode")
        );
        return "dalai_llama".equals(provider) ? "dalai_llama" : "synthesia";
    }

    Map<String, Object> founderAvatarProfile(
            Map<String, Object> request,
            Map<String, Object> scriptPayload,
            Map<String, Object> creatorContext
    ) {
        Map<String, Object> profile = firstMap(
                request == null ? null : request.get("founderAvatarProfile"),
                request == null ? null : request.get("founderKit"),
                scriptPayload == null ? null : scriptPayload.get("founderAvatarProfile"),
                scriptPayload == null ? null : scriptPayload.get("founderKit"),
                creatorContext == null ? null : creatorContext.get("founderAvatarProfile"),
                creatorContext == null ? null : creatorContext.get("founderKit")
        );
        String providerMode = avatarProviderFrom(
                request == null ? null : request.get("avatarProviderMode"),
                request == null ? null : request.get("avatarProvider"),
                profile.get("avatarProviderMode"),
                profile.get("providerMode"),
                scriptPayload == null ? null : scriptPayload.get("avatarProviderMode"),
                creatorContext == null ? null : creatorContext.get("avatarProviderMode")
        );
        profile.put("avatarProviderMode", providerMode);
        profile.put("providerMode", providerMode);
        putIfBlank(profile, "avatarId", firstText(
                request == null ? null : request.get("avatarId"),
                request == null ? null : request.get("synthesiaAvatarId"),
                scriptPayload == null ? null : scriptPayload.get("avatarId"),
                creatorContext == null ? null : creatorContext.get("avatarId")
        ));
        putIfBlank(profile, "voiceId", firstText(
                request == null ? null : request.get("voiceId"),
                request == null ? null : request.get("synthesiaVoiceId"),
                scriptPayload == null ? null : scriptPayload.get("voiceId"),
                creatorContext == null ? null : creatorContext.get("voiceId")
        ));
        putIfBlank(profile, "portraitEmbeddingId", firstText(request == null ? null : request.get("portraitEmbeddingId"), scriptPayload == null ? null : scriptPayload.get("portraitEmbeddingId")));
        putIfBlank(profile, "facialFeatureEmbeddingId", firstText(request == null ? null : request.get("facialFeatureEmbeddingId"), scriptPayload == null ? null : scriptPayload.get("facialFeatureEmbeddingId")));
        putIfBlank(profile, "voiceEmbeddingId", firstText(request == null ? null : request.get("voiceEmbeddingId"), scriptPayload == null ? null : scriptPayload.get("voiceEmbeddingId")));
        putIfBlank(profile, "voiceProfileId", firstText(
                request == null ? null : request.get("voiceProfileId"),
                firstMap(request == null ? null : request.get("localModels")).get("voiceProfileId")
        ));
        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(
                request == null ? null : request.get("localAvatarModels"),
                request == null ? null : request.get("localModels"),
                profile.get("localModels")
        ));
        if (localModels.isEmpty()) {
            localModels = founderLocalModels("", "", "", "", "");
        } else {
            localModels.put("voiceModel", normalizeLocalVoiceModel(firstText(localModels.get("voiceModel"))));
            localModels.put("talkingAvatarModel", normalizeLocalTalkingAvatarModel(firstText(localModels.get("talkingAvatarModel"))));
            localModels.put("lipSyncModel", normalizeLocalLipSyncModel(firstText(localModels.get("lipSyncModel"), localModels.get("lipsyncModel"))));
            localModels.put("imageModel", normalizeLocalImageModel(firstText(localModels.get("imageModel"))));
            localModels.put("lightingModel", firstText(localModels.get("lightingModel"), "ic_lightning"));
            localModels.put("videoModel", normalizeLocalVideoModel(firstText(localModels.get("videoModel"))));
            localModels.put("gpuProfile", firstText(localModels.get("gpuProfile"), "rtx_4060_8gb"));
        }
        profile.put("localModels", localModels);
        String requestedLanguage = firstText(
                request == null ? null : request.get("dialogueLanguage"),
                request == null ? null : request.get("language")
        );
        profile.put("language", firstText(requestedLanguage, profile.get("language"), "Hinglish"));
        profile.put("languageCode", firstText(
                request == null ? null : request.get("languageCode"),
                languageCodeFor(requestedLanguage),
                profile.get("languageCode"),
                "hi-IN"
        ));
        refreshFounderSourceUrl(profile);
        return profile;
    }

    private void refreshFounderSourceUrl(Map<String, Object> profile) {
        Map<String, Object> sourceAsset = copyMap(profile.get("sourceAsset"));
        String objectKey = firstText(sourceAsset.get("objectKey"), profile.get("sourceObjectKey"));
        if (!objectKey.isBlank()) {
            String bucket = firstText(sourceAsset.get("bucket"), assetStorageService.creatorAssetsBucket());
            try {
                String signedUrl = assetStorageService.signedUrl(bucket, objectKey, SIGNED_URL_TTL);
                sourceAsset.put("bucket", bucket);
                sourceAsset.put("objectKey", objectKey);
                sourceAsset.put("assetUrl", signedUrl);
                sourceAsset.put("signedUrl", signedUrl);
                sourceAsset.put("publicUrl", signedUrl);
                sourceAsset.put("signedUrlTtlSeconds", SIGNED_URL_TTL.toSeconds());
                sourceAsset.put("signedUrlRefreshedAt", OffsetDateTime.now().toString());
                profile.put("sourceAsset", sourceAsset);
                profile.put("sourceUrl", signedUrl);
            } catch (RuntimeException ex) {
                log.warn("Could not refresh founder source signed URL bucket={} objectKey={} errorType={} errorMessage={}",
                        bucket, objectKey, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }

        Map<String, Object> finalAudioAsset = firstMap(profile.get("exactFounderAudioAsset"), profile.get("finalFounderAudioAsset"));
        String finalAudioObjectKey = firstText(finalAudioAsset.get("objectKey"));
        if (!finalAudioObjectKey.isBlank()) {
            String bucket = firstText(finalAudioAsset.get("bucket"), assetStorageService.creatorAssetsBucket());
            try {
                String signedUrl = assetStorageService.signedUrl(bucket, finalAudioObjectKey, SIGNED_URL_TTL);
                finalAudioAsset.put("bucket", bucket);
                finalAudioAsset.put("assetUrl", signedUrl);
                finalAudioAsset.put("signedUrl", signedUrl);
                finalAudioAsset.put("publicUrl", signedUrl);
                finalAudioAsset.put("signedUrlRefreshedAt", OffsetDateTime.now().toString());
                profile.put("exactFounderAudioAsset", finalAudioAsset);
                profile.put("finalFounderAudioAsset", finalAudioAsset);
                profile.put("finalFounderAudioUrl", signedUrl);
            } catch (RuntimeException ex) {
                log.warn("Could not refresh founder final audio signed URL bucket={} objectKey={} errorType={} errorMessage={}",
                        bucket, finalAudioObjectKey, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }

    String normalizeVideoProvider(String provider) {
        return videoProviderCatalog().normalizeVideoProvider(provider);
    }

    String modelForProvider(String provider, String requestedModel) {
        return videoProviderCatalog().modelForProvider(provider, requestedModel);
    }

    private int modelCapabilityMaxClipSeconds(String provider, String model, Object requestedMaxClipSeconds) {
        return videoProviderCatalog().modelCapabilityMaxClipSeconds(provider, model, requestedMaxClipSeconds);
    }

    int defaultMaxClipSecondsForProvider(String provider) {
        return videoProviderCatalog().defaultMaxClipSecondsForProvider(provider);
    }

    int defaultMaxClipSecondsForProvider(String provider, String model) {
        return videoProviderCatalog().defaultMaxClipSecondsForProvider(provider, model);
    }

    String googleVeoBaseUrl(String model) {
        return videoProviderCatalog().googleVeoBaseUrl(model);
    }

    private VideoProviderCatalog videoProviderCatalog() {
        return new VideoProviderCatalog();
    }

    String normalizeProductionStyle(String value) {
        String normalized = defaultString(value, "hybrid").toLowerCase(Locale.ROOT).replace('-', '_').trim();
        if (normalized.equals("full_ai") || normalized.equals("all_ai") || normalized.equals("ai_only") || normalized.equals("seedance_only")) {
            return "full_ai";
        }
        return "hybrid";
    }

    private String normalizeGenerationMode(String value) {
        String normalized = defaultString(value, "ai_generated").toLowerCase(Locale.ROOT).replace('-', '_').trim();
        if (normalized.contains("talking") || normalized.contains("human") || normalized.contains("record")) {
            return "talking_head";
        }
        return "ai_generated";
    }

    String srtTime(int seconds) {
        int safe = Math.max(0, seconds);
        int hours = safe / 3600;
        int minutes = (safe % 3600) / 60;
        int secs = safe % 60;
        return "%02d:%02d:%02d,000".formatted(hours, minutes, secs);
    }

    /**
     * The frontend resubmits the script's full screenplayJson (including its bounded
     * clientReviewPlanningSnapshots undo history) every time a video generation job is started.
     * Generation only needs the current shot plan, not the review history, and that history is
     * already durable in the script's own storyboard/shot-plan tables — so it doesn't need a
     * second copy riding along on every generation job's input_payload.
     */
    @SuppressWarnings("unchecked")
    private void trimClientReviewHistory(Map<String, Object> inputPayload) {
        Object screenplayJson = inputPayload == null ? null : inputPayload.get("screenplayJson");
        if (screenplayJson instanceof Map<?, ?> screenplayMap && screenplayMap.containsKey("clientReviewPlanningSnapshots")) {
            Map<String, Object> trimmed = new LinkedHashMap<>((Map<String, Object>) screenplayMap);
            trimmed.remove("clientReviewPlanningSnapshots");
            inputPayload.put("screenplayJson", trimmed);
        }
    }

    record RunRecord(CreatorGenerationJob job, Map<String, Object> run) {
    }
}
