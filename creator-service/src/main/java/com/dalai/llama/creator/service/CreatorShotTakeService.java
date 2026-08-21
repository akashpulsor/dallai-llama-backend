package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.request.EnhanceAllShotTakesRequest;
import com.dalai.llama.creator.dto.request.ShotTakeAudioEnhanceRequest;
import com.dalai.llama.creator.dto.request.ShotTakeAudioMixRequest;
import com.dalai.llama.creator.dto.request.ShotTakeConfirmRequest;
import com.dalai.llama.creator.dto.request.ShotTakeEnhanceFeedbackRequest;
import com.dalai.llama.creator.dto.request.ShotTakeEnhancePreviewRequest;
import com.dalai.llama.creator.dto.request.ShotTakeFinalRenderRequest;
import com.dalai.llama.creator.dto.request.ShotTakeMediaAnalysisRequest;
import com.dalai.llama.creator.dto.request.ShotTakePolishedFramesRequest;
import com.dalai.llama.creator.dto.request.ShotTakeSoundGenerateRequest;
import com.dalai.llama.creator.dto.request.ShotTakeSoundTimelineRequest;
import com.dalai.llama.creator.dto.request.ShotTakeStudioPolishRequest;
import com.dalai.llama.creator.dto.response.ShotTakeEnhancementVariantResponse;
import com.dalai.llama.creator.dto.response.ShotTakeResponse;
import com.dalai.llama.creator.dto.response.ShotTakeReviewResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class CreatorShotTakeService {

    private static final Logger log = LoggerFactory.getLogger(CreatorShotTakeService.class);
    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    private static final String ASSET_TYPE_USER_SHOT_TAKE = "USER_SHOT_TAKE";
    private static final String ASSET_TYPE_SHOT_TAKE_REFERENCE_FRAME = "SHOT_TAKE_REFERENCE_FRAME";
    private static final String ASSET_TYPE_SHOT_ENHANCEMENT_PREVIEW = "SHOT_ENHANCEMENT_PREVIEW_IMAGE";
    private static final String ASSET_TYPE_SHOT_ENHANCEMENT_VIDEO = "SHOT_ENHANCEMENT_VIDEO";
    private static final String ASSET_TYPE_SHOT_STUDIO_POLISH_PLATE = "SHOT_STUDIO_POLISH_PLATE_IMAGE";
    private static final String ASSET_TYPE_SHOT_STUDIO_POLISH_VIDEO = "SHOT_STUDIO_POLISH_VIDEO";
    private static final String ASSET_TYPE_SHOT_TAKE_ENHANCED_AUDIO = "SHOT_TAKE_ENHANCED_AUDIO";
    private static final String ASSET_TYPE_SHOT_TAKE_MIXED_AUDIO = "SHOT_TAKE_MIXED_AUDIO";
    private static final String ASSET_TYPE_SHOT_TAKE_FINAL_RENDER_VIDEO = "SHOT_TAKE_FINAL_RENDER_VIDEO";
    private static final String ASSET_TYPE_SHOT_TAKE_FINAL_SEQUENCE_VIDEO = "SHOT_TAKE_FINAL_SEQUENCE_VIDEO";
    private static final String ASSET_TYPE_SHOT_TAKE_SOUND_SNIPPET = "SHOT_TAKE_SOUND_SNIPPET";
    private static final String ASSET_TYPE_SHOT_TAKE_GENERATED_SOUND = "SHOT_TAKE_GENERATED_SOUND";
    private static final Set<String> AUDIO_ENHANCEMENT_SOURCE_ASSET_TYPES = Set.of(
            ASSET_TYPE_USER_SHOT_TAKE,
            ASSET_TYPE_SHOT_TAKE_SOUND_SNIPPET,
            ASSET_TYPE_SHOT_TAKE_GENERATED_SOUND,
            ASSET_TYPE_SHOT_TAKE_ENHANCED_AUDIO,
            ASSET_TYPE_SHOT_TAKE_MIXED_AUDIO
    );
    private static final String DEFAULT_STYLE_KEY = "indian_creator_pencil";

    private final CreatorScriptRepository scriptRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final StoryboardImageGenerationService imageGenerationService;
    private final StudioPolishVideoGenerationService studioPolishVideoGenerationService;
    private final GoogleLyriaMusicGenerationService musicGenerationService;
    private final LocalAudioMixService localAudioMixService;
    private final LocalAudioEnhancementService localAudioEnhancementService;
    private final LocalVideoFrameExtractionService localVideoFrameExtractionService;
    private final LocalFinalVideoRenderService localFinalVideoRenderService;
    private final LocalShotSequenceRenderService localShotSequenceRenderService;
    private final LocalRunwayVideoNormalizationService localRunwayVideoNormalizationService;
    private final StudioPolishProviderQueueService studioPolishProviderQueueService;
    private final GenerationJobService generationJobService;
    private final CreatorAiPricingService pricingService;
    private final CreatorAiService creatorAiService;
    private final ProviderCreditHealthService providerCreditHealthService;
    private final CreatorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CreatorShotTakeService(
            CreatorScriptRepository scriptRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            StoryboardImageGenerationService imageGenerationService,
            StudioPolishVideoGenerationService studioPolishVideoGenerationService,
            GoogleLyriaMusicGenerationService musicGenerationService,
            LocalAudioMixService localAudioMixService,
            LocalAudioEnhancementService localAudioEnhancementService,
            LocalVideoFrameExtractionService localVideoFrameExtractionService,
            LocalFinalVideoRenderService localFinalVideoRenderService,
            LocalShotSequenceRenderService localShotSequenceRenderService,
            LocalRunwayVideoNormalizationService localRunwayVideoNormalizationService,
            StudioPolishProviderQueueService studioPolishProviderQueueService,
            GenerationJobService generationJobService,
            CreatorAiPricingService pricingService,
            CreatorAiService creatorAiService,
            ProviderCreditHealthService providerCreditHealthService,
            CreatorProperties properties,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.scriptRepository = scriptRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.imageGenerationService = imageGenerationService;
        this.studioPolishVideoGenerationService = studioPolishVideoGenerationService;
        this.musicGenerationService = musicGenerationService;
        this.localAudioMixService = localAudioMixService;
        this.localAudioEnhancementService = localAudioEnhancementService;
        this.localVideoFrameExtractionService = localVideoFrameExtractionService;
        this.localFinalVideoRenderService = localFinalVideoRenderService;
        this.localShotSequenceRenderService = localShotSequenceRenderService;
        this.localRunwayVideoNormalizationService = localRunwayVideoNormalizationService;
        this.studioPolishProviderQueueService = studioPolishProviderQueueService;
        this.generationJobService = generationJobService;
        this.pricingService = pricingService;
        this.creatorAiService = creatorAiService;
        this.providerCreditHealthService = providerCreditHealthService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ShotTakeResponse> listTakes(UUID scriptId, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        // Was: one row-fetching query for the list, then toTakeResponse(id) PER ROW,
        // which itself re-queried the same take row plus reviews plus variants -
        // 1 + 3*N queries for N takes on every project open. Now: 1 query for the
        // rows (already has everything toTakeResponse would have re-fetched) + 2
        // batched queries for reviews/variants across every take at once.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                takeSelectSql() + """
                        where t.script_id = ?
                          and t.tenant_id = ?
                          and t.user_id = ?
                        order by t.shot_number asc, t.created_at desc
                        """,
                script.getId(),
                safeTenantId(tenantId),
                safeUserId(userId)
        );
        List<UUID> takeIds = rows.stream().map(row -> uuidValue(row.get("id"))).toList();
        Map<UUID, List<ShotTakeReviewResponse>> reviewsByTake = groupById(
                loadReviewsForTakes(takeIds), ShotTakeReviewResponse::takeId
        );
        Map<UUID, List<ShotTakeEnhancementVariantResponse>> variantsByTake = groupById(
                loadVariantsForTakes(takeIds), ShotTakeEnhancementVariantResponse::takeId
        );
        return rows.stream()
                .map(row -> buildTakeResponseFromRow(row, reviewsByTake, variantsByTake))
                .toList();
    }

    private <T> Map<UUID, List<T>> groupById(List<T> items, java.util.function.Function<T, UUID> idExtractor) {
        Map<UUID, List<T>> grouped = new LinkedHashMap<>();
        for (T item : items) {
            grouped.computeIfAbsent(idExtractor.apply(item), key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private ShotTakeResponse buildTakeResponseFromRow(
            Map<String, Object> row,
            Map<UUID, List<ShotTakeReviewResponse>> reviewsByTake,
            Map<UUID, List<ShotTakeEnhancementVariantResponse>> variantsByTake
    ) {
        UUID takeId = uuidValue(row.get("id"));
        UUID assetId = uuidValue(row.get("asset_id"));
        String assetUrl = signedUrl(stringValue(row.get("bucket"), ""), stringValue(row.get("object_key"), ""), stringValue(row.get("public_url"), ""));
        UUID referenceFrameAssetId = uuidValue(row.get("reference_frame_asset_id"));
        String referenceFrameUrl = signedUrl(
                stringValue(row.get("reference_frame_bucket"), ""),
                stringValue(row.get("reference_frame_object_key"), ""),
                stringValue(row.get("reference_frame_public_url"), "")
        );
        return new ShotTakeResponse(
                takeId,
                uuidValue(row.get("project_id")),
                uuidValue(row.get("script_id")),
                numberValue(row.get("shot_number"), 0),
                assetId,
                assetUrl,
                stringValue(row.get("content_type"), ""),
                longValue(row.get("size_bytes")),
                referenceFrameAssetId,
                referenceFrameUrl,
                stringValue(row.get("reference_frame_content_type"), ""),
                stringValue(row.get("status"), ""),
                stringValue(row.get("review_status"), ""),
                Boolean.TRUE.equals(row.get("accepted")),
                stringValue(row.get("user_notes"), ""),
                readMap(stringValue(row.get("media_analysis"), "{}")),
                readMap(stringValue(row.get("validation_summary"), "{}")),
                reviewsByTake.getOrDefault(takeId, List.of()),
                variantsByTake.getOrDefault(takeId, List.of()),
                offsetDateTime(row.get("created_at")),
                offsetDateTime(row.get("updated_at"))
        );
    }

    private List<ShotTakeReviewResponse> loadReviewsForTakes(List<UUID> takeIds) {
        if (takeIds.isEmpty()) {
            return List.of();
        }
        String placeholders = takeIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        return jdbcTemplate.query(
                """
                select id, take_id, generation_job_id, status, score, checks::text as checks,
                       sound_timeline::text as sound_timeline, message, created_at
                from creator_shot_take_reviews
                where take_id in (%s)
                order by created_at desc
                """.formatted(placeholders),
                (rs, rowNum) -> new ShotTakeReviewResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("take_id", UUID.class),
                        rs.getObject("generation_job_id", UUID.class),
                        rs.getString("status"),
                        rs.getBigDecimal("score") == null ? null : rs.getBigDecimal("score").doubleValue(),
                        readMap(rs.getString("checks")),
                        readList(rs.getString("sound_timeline")),
                        rs.getString("message"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                takeIds.toArray()
        );
    }

    private List<ShotTakeEnhancementVariantResponse> loadVariantsForTakes(List<UUID> takeIds) {
        if (takeIds.isEmpty()) {
            return List.of();
        }
        String placeholders = takeIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        return jdbcTemplate.query(
                """
                select v.id, v.take_id, v.generation_job_id, v.preview_asset_id, v.status,
                       v.final_video_asset_id, v.final_audio_asset_id, v.final_render_asset_id, v.provider, v.provider_operation_id,
                       v.prompt_payload::text as prompt_payload, v.provider_response::text as provider_response,
                       v.user_feedback, v.created_at, v.updated_at,
                       a.bucket as preview_bucket, a.object_key as preview_object_key, a.public_url as preview_public_url,
                       va.bucket as final_video_bucket, va.object_key as final_video_object_key, va.public_url as final_video_public_url,
                       aa.bucket as final_audio_bucket, aa.object_key as final_audio_object_key, aa.public_url as final_audio_public_url,
                       fra.bucket as final_render_bucket, fra.object_key as final_render_object_key, fra.public_url as final_render_public_url
                from creator_shot_enhancement_variants v
                left join creator_assets a on a.id = v.preview_asset_id
                left join creator_assets va on va.id = v.final_video_asset_id
                left join creator_assets aa on aa.id = v.final_audio_asset_id
                left join creator_assets fra on fra.id = v.final_render_asset_id
                where v.take_id in (%s)
                order by v.created_at desc
                """.formatted(placeholders),
                (rs, rowNum) -> new ShotTakeEnhancementVariantResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("take_id", UUID.class),
                        rs.getObject("generation_job_id", UUID.class),
                        rs.getObject("preview_asset_id", UUID.class),
                        signedUrl(rs.getString("preview_bucket"), rs.getString("preview_object_key"), rs.getString("preview_public_url")),
                        rs.getObject("final_video_asset_id", UUID.class),
                        signedUrl(rs.getString("final_video_bucket"), rs.getString("final_video_object_key"), rs.getString("final_video_public_url")),
                        rs.getObject("final_audio_asset_id", UUID.class),
                        signedUrl(rs.getString("final_audio_bucket"), rs.getString("final_audio_object_key"), rs.getString("final_audio_public_url")),
                        rs.getObject("final_render_asset_id", UUID.class),
                        signedUrl(rs.getString("final_render_bucket"), rs.getString("final_render_object_key"), rs.getString("final_render_public_url")),
                        rs.getString("status"),
                        rs.getString("provider"),
                        rs.getString("provider_operation_id"),
                        readMap(rs.getString("prompt_payload")),
                        readMap(rs.getString("provider_response")),
                        rs.getString("user_feedback"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                takeIds.toArray()
        );
    }

    @Transactional
    public ShotTakeResponse uploadTake(UUID scriptId, int shotNumber, MultipartFile file, String note, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a shot file before review.");
        }
        String contentType = defaultString(file.getContentType(), "application/octet-stream");
        String objectKey = "shot-takes/%s/%02d/%s-%s".formatted(
                scriptId,
                Math.max(1, shotNumber),
                UUID.randomUUID(),
                sanitizeFilename(file.getOriginalFilename())
        );

        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded shot file.", ex);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", scriptId.toString());
        metadata.put("shotNumber", shotNumber);
        metadata.put("source", "user_upload");
        metadata.put("originalFilename", defaultString(file.getOriginalFilename(), "shot-upload"));
        metadata.put("reviewPurpose", "creator_shot_take");

        CreatorAsset asset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_USER_SHOT_TAKE)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        UUID takeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into creator_shot_takes (
                    id, tenant_id, user_id, project_id, script_id, shot_number, asset_id,
                    status, review_status, accepted, user_notes, validation_summary, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, 'UPLOADED', 'PENDING', false, ?, '{}'::jsonb, now(), now())
                """,
                takeId,
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                script.getId(),
                Math.max(1, shotNumber),
                asset.getId(),
                defaultString(note, "")
        );
        return toTakeResponse(takeId);
    }

    @Transactional
    public ShotTakeResponse uploadReferenceFrame(UUID takeId, MultipartFile file, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        CreatorScript script = loadScript(take.scriptId(), tenantId, userId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a still frame before generating an image-wise polish preview.");
        }
        String contentType = defaultString(file.getContentType(), "application/octet-stream");
        if (!isImageContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reference frame must be an image file.");
        }

        String objectKey = "shot-takes/%s/%02d/reference-frames/%s-%s".formatted(
                script.getId(),
                Math.max(1, take.shotNumber()),
                UUID.randomUUID(),
                sanitizeFilename(file.getOriginalFilename())
        );

        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded reference frame.", ex);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("shotNumber", take.shotNumber());
        metadata.put("takeId", take.id().toString());
        metadata.put("source", "user_reference_frame");
        metadata.put("purpose", "gemini_image_wise_polish_anchor");
        metadata.put("originalFilename", defaultString(file.getOriginalFilename(), "reference-frame"));

        CreatorAsset referenceAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(take.tenantId())
                .userId(take.userId())
                .projectId(take.projectId())
                .assetType(ASSET_TYPE_SHOT_TAKE_REFERENCE_FRAME)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        jdbcTemplate.update(
                """
                update creator_shot_takes
                set reference_frame_asset_id = ?,
                    updated_at = now()
                where id = ?
                """,
                referenceAsset.getId(),
                take.id()
        );
        return toTakeResponse(take.id());
    }

    @Transactional
    public ShotTakeResponse saveMediaAnalysis(UUID takeId, ShotTakeMediaAnalysisRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        Map<String, Object> analysis = request == null || request.mediaAnalysis() == null
                ? Map.of()
                : new LinkedHashMap<>(request.mediaAnalysis());
        jdbcTemplate.update(
                """
                update creator_shot_takes
                set media_analysis = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                writeJson(analysis),
                take.id()
        );
        return toTakeResponse(take.id());
    }

    @Transactional
    public ShotTakeResponse saveSoundTimeline(UUID takeId, ShotTakeSoundTimelineRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        Map<String, Object> analysis = loadTakeMediaAnalysis(take.id());
        Map<String, Object> timeline = normalizeSoundTimeline(
                request == null ? List.<Map<String, Object>>of() : request.layers(),
                request == null ? Map.<String, Object>of() : request.mixSettings(),
                analysis.get("soundTimeline")
        );
        analysis.put("soundTimeline", timeline);
        analysis.put("audioMix", timeline.get("mixSettings"));
        analysis.put("updatedAt", OffsetDateTime.now().toString());
        saveTakeMediaAnalysis(take.id(), analysis);
        return toTakeResponse(take.id());
    }

    @Transactional
    public ShotTakeResponse uploadSoundSnippet(UUID takeId, MultipartFile file, String metadataJson, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a foley, ambience, music, or sound-effect snippet.");
        }
        String contentType = defaultString(file.getContentType(), "audio/mpeg");
        if (!isAudioContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sound snippet must be an audio file.");
        }

        Map<String, Object> requestMetadata = readMap(defaultString(metadataJson, "{}"));
        String objectKey = "shot-takes/%s/%02d/sound-snippets/%s-%s".formatted(
                take.scriptId(),
                Math.max(1, take.shotNumber()),
                UUID.randomUUID(),
                sanitizeFilename(file.getOriginalFilename())
        );
        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded sound snippet.", ex);
        }

        Map<String, Object> assetMetadata = new LinkedHashMap<>(requestMetadata);
        assetMetadata.put("scriptId", take.scriptId().toString());
        assetMetadata.put("shotNumber", take.shotNumber());
        assetMetadata.put("takeId", take.id().toString());
        assetMetadata.put("source", "user_sound_snippet");
        assetMetadata.put("purpose", "shot_take_sound_timeline_layer");
        assetMetadata.put("originalFilename", defaultString(file.getOriginalFilename(), "sound-snippet"));

        CreatorAsset snippetAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(take.tenantId())
                .userId(take.userId())
                .projectId(take.projectId())
                .assetType(ASSET_TYPE_SHOT_TAKE_SOUND_SNIPPET)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(assetMetadata)
                .build());

        Map<String, Object> analysis = loadTakeMediaAnalysis(take.id());
        Map<String, Object> timeline = normalizeSoundTimeline(null, null, analysis.get("soundTimeline"));
        List<Map<String, Object>> layers = soundLayerList(timeline.get("layers"));
        Map<String, Object> snippetLayer = new LinkedHashMap<>(requestMetadata);
        snippetLayer.put("id", UUID.randomUUID().toString());
        snippetLayer.put("source", "user_upload");
        snippetLayer.put("assetId", snippetAsset.getId().toString());
        snippetLayer.put("assetUrl", signedUrl(stored.bucket(), stored.objectKey(), stored.signedUrl()));
        snippetLayer.put("contentType", stored.contentType());
        snippetLayer.put("sizeBytes", stored.sizeBytes());
        snippetLayer.put("originalFilename", defaultString(file.getOriginalFilename(), "sound-snippet"));
        layers.add(normalizeSoundLayer(snippetLayer, layers.size()));
        timeline.put("layers", layers);
        timeline.put("updatedAt", OffsetDateTime.now().toString());
        analysis.put("soundTimeline", timeline);
        analysis.put("audioMix", timeline.get("mixSettings"));
        analysis.put("updatedAt", OffsetDateTime.now().toString());
        saveTakeMediaAnalysis(take.id(), analysis);
        return toTakeResponse(take.id());
    }

    @Transactional
    public CreatorGenerationJob startSoundGenerationJob(UUID takeId, ShotTakeSoundGenerateRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        if (request == null || defaultString(request.prompt(), "").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Describe the music, foley, ambience, or SFX to generate.");
        }
        validateLyriaSoundGenerationRequest(request);

        String layerId = UUID.randomUUID().toString();
        Map<String, Object> pendingLayer = buildGeneratedSoundLayer(take, request, layerId, null, "GENERATION_PENDING");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("takeId", take.id().toString());
        input.put("scriptId", take.scriptId().toString());
        input.put("shotNumber", take.shotNumber());
        input.put("layerId", layerId);
        input.put("mode", "SOUND_GENERATE_TIMELINE_CLIP");
        input.put("provider", resolveSoundGenerationProvider(request));
        input.put("model", resolveSoundGenerationModel(request));
        input.put("prompt", request.prompt());
        input.put("pendingLayer", pendingLayer);

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                "SHOT_TAKE_SOUND_GENERATE",
                take.tenantId(),
                take.userId(),
                take.projectId(),
                input
        );

        pendingLayer.put("generationJobId", job.getId().toString());
        appendOrReplaceSoundTimelineLayer(take.id(), pendingLayer);
        return job;
    }

    @Transactional
    public ShotTakeResponse runSoundGenerationJob(UUID jobId, UUID takeId, ShotTakeSoundGenerateRequest request, String tenantId, String userId) {
        validateLyriaSoundGenerationRequest(request);
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        CreatorScript script = loadScript(take.scriptId(), tenantId, userId);
        CreatorScriptShotPlan plan = loadShotPlan(script.getId(), take.shotNumber());
        Map<String, Object> jobInput = takeJobInput(jobId);
        String layerId = defaultString(stringValue(jobInput.get("layerId"), ""), UUID.randomUUID().toString());
        Map<String, Object> providerTask = buildSoundGenerationProviderTask(script, plan, take, request, jobId, layerId);
        Map<String, Object> pendingLayer = buildGeneratedSoundLayer(take, request, layerId, jobId, "GENERATION_TASK_READY");
        appendOrReplaceSoundTimelineLayer(take.id(), pendingLayer);

        GoogleLyriaMusicGenerationService.GeneratedAudio generatedAudio = musicGenerationService.generateMusic(
                stringValue(providerTask.get("lyriaPrompt"), defaultString(request == null ? null : request.prompt(), "")),
                stringValue(providerTask.get("negativePrompt"), ""),
                stringValue(providerTask.get("layerType"), "music"),
                decimalValue(providerTask.get("durationSeconds"), request == null || request.durationSeconds() == null ? 4.0 : request.durationSeconds())
        );
        publishProviderUsageFromMetadata(
                "SHOT_TAKE_SOUND_GENERATE",
                generatedAudio.metadata(),
                take,
                jobId,
                "Generated timeline sound clip"
        );
        Map<String, Object> generatedMetadata = new LinkedHashMap<>(generatedAudio.metadata());
        generatedMetadata.put("providerTask", providerTask);
        generatedMetadata.put("sourcePrompt", defaultString(request == null ? null : request.prompt(), ""));
        generatedMetadata.put("layerType", stringValue(providerTask.get("layerType"), "music"));
        Map<String, Object> costMetadata = objectMap(generatedMetadata.get("costMetadata"));
        Map<String, Object> tokenMetadata = objectMap(generatedMetadata.get("tokenMetadata"));
        return storeGeneratedSound(
                take,
                jobId,
                layerId,
                generatedAudio.bytes(),
                generatedAudio.contentType(),
                "lyria-%s.%s".formatted(jobId, audioFileExtension(generatedAudio.contentType())),
                generatedMetadata,
                Map.of(
                        "provider", "google_lyria",
                        "model", properties.getAi().getLyriaMusicModel(),
                        "providerTask", providerTask,
                        "providerRequest", generatedAudio.providerRequest(),
                        "providerResponseMetadata", generatedAudio.metadata(),
                        "costMetadata", costMetadata,
                        "tokenMetadata", tokenMetadata,
                        "message", "Lyria generated the requested timeline sound clip."
                )
        );
    }

    @Transactional
    public ShotTakeResponse completeSoundGeneration(
            UUID takeId,
            UUID jobId,
            String layerId,
            MultipartFile file,
            String metadataJson,
            String tenantId,
            String userId
    ) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        ensureGenerationJobBelongsToTake(jobId, take.id());
        if (defaultString(layerId, "").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "layerId is required.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the generated sound clip.");
        }
        String contentType = defaultString(file.getContentType(), "audio/wav");
        if (!isAudioContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generated sound output must be an audio file.");
        }
        try {
            return storeGeneratedSound(
                    take,
                    jobId,
                    layerId,
                    file.getBytes(),
                    contentType,
                    sanitizeFilename(file.getOriginalFilename()),
                    readMap(defaultString(metadataJson, "{}")),
                    Map.of("message", "Generated sound clip uploaded.")
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read generated sound file.", ex);
        }
    }

    private ShotTakeResponse storeGeneratedSound(
            ShotTakeData take,
            UUID jobId,
            String layerId,
            byte[] audioBytes,
            String contentType,
            String filename,
            Map<String, Object> rawMetadata,
            Map<String, Object> jobOutputExtras
    ) {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generated sound output was empty.");
        }
        String normalizedContentType = defaultString(contentType, "audio/wav");
        if (!isAudioContentType(normalizedContentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generated sound output must be an audio file.");
        }
        String objectKey = "shot-takes/%s/%02d/generated-sound/%s-%s".formatted(
                take.scriptId(),
                take.shotNumber(),
                jobId,
                sanitizeFilename(defaultString(filename, "generated-sound.%s".formatted(audioFileExtension(normalizedContentType))))
        );
        AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAsset(
                objectKey,
                audioBytes,
                normalizedContentType,
                SIGNED_URL_TTL
        );

        Map<String, Object> metadata = new LinkedHashMap<>(rawMetadata == null ? Map.of() : rawMetadata);
        metadata.put("takeId", take.id().toString());
        metadata.put("scriptId", take.scriptId().toString());
        metadata.put("shotNumber", take.shotNumber());
        metadata.put("generationJobId", jobId.toString());
        metadata.put("layerId", layerId);
        metadata.put("source", "ai_generated_sound");
        metadata.put("assetKind", "shot_take_generated_sound_clip");

        CreatorAsset generatedSound = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(take.tenantId())
                .userId(take.userId())
                .projectId(take.projectId())
                .assetType(ASSET_TYPE_SHOT_TAKE_GENERATED_SOUND)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> completedLayer = new LinkedHashMap<>(metadata);
        completedLayer.put("id", layerId);
        completedLayer.put("source", "ai_generated");
        completedLayer.put("status", "READY");
        completedLayer.put("assetId", generatedSound.getId().toString());
        completedLayer.put("assetUrl", signedUrl(stored.bucket(), stored.objectKey(), stored.signedUrl()));
        completedLayer.put("contentType", stored.contentType());
        completedLayer.put("sizeBytes", stored.sizeBytes());
        appendOrReplaceSoundTimelineLayer(take.id(), completedLayer);

        Map<String, Object> output = new LinkedHashMap<>();
        output.putAll(jobOutputExtras == null ? Map.of() : jobOutputExtras);
        output.put("takeId", take.id().toString());
        output.put("scriptId", take.scriptId().toString());
        output.put("shotNumber", take.shotNumber());
        output.put("layerId", layerId);
        output.put("status", "READY");
        output.put("generatedLayer", completedLayer);
        generationJobService.completeGenerationJob(jobId, output);
        return toTakeResponse(take.id());
    }

    @Transactional
    public CreatorGenerationJob startReviewJob(UUID takeId, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("takeId", take.id().toString());
        input.put("scriptId", take.scriptId().toString());
        input.put("shotNumber", take.shotNumber());
        input.put("mode", "deterministic_no_llm");
        return generationJobService.startGenerationJob(
                "SHOT_TAKE_REVIEW",
                take.tenantId(),
                take.userId(),
                take.projectId(),
                input
        );
    }

    @Transactional
    public ShotTakeResponse runReviewJob(UUID jobId, UUID takeId, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        CreatorScript script = loadScript(take.scriptId(), tenantId, userId);
        CreatorScriptShotPlan plan = loadShotPlan(script.getId(), take.shotNumber());
        Map<String, Object> checks = buildDeterministicChecks(take, plan);
        List<Map<String, Object>> soundTimeline = buildSoundTimeline(script, plan, take.shotNumber());
        boolean hardFail = Boolean.TRUE.equals(checks.get("hardFail"));
        String reviewStatus = hardFail ? "NEEDS_RESHOOT" : "AUTO_PASSED_NEEDS_CONFIRMATION";
        String status = hardFail ? "NEEDS_RESHOOT" : "REVIEWED";
        double score = hardFail ? 0.35 : 0.82;
        String message = hardFail
                ? "Uploaded file failed deterministic checks. Please re-shoot or upload a supported shot."
                : "Auto checks passed. Confirm actor, dialogue, and camera angle manually before polish.";

        Map<String, Object> validationSummary = new LinkedHashMap<>();
        validationSummary.put("status", reviewStatus);
        validationSummary.put("score", score);
        validationSummary.put("message", message);
        validationSummary.put("checks", checks);
        validationSummary.put("soundTimeline", soundTimeline);
        validationSummary.put("requiresManualConfirmation", !hardFail);
        validationSummary.put("manualChecks", List.of("actor_presence", "dialogue_matches_script", "camera_angle_matches_plan", "expression_matches_beat"));

        jdbcTemplate.update(
                """
                update creator_shot_takes
                set status = ?,
                    review_status = ?,
                    validation_summary = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                status,
                reviewStatus,
                writeJson(validationSummary),
                take.id()
        );

        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into creator_shot_take_reviews (
                    id, take_id, generation_job_id, status, score, checks, sound_timeline, message, created_at
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, now())
                """,
                reviewId,
                take.id(),
                jobId,
                reviewStatus,
                BigDecimal.valueOf(score),
                writeJson(checks),
                writeJson(soundTimeline),
                message
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("takeId", take.id().toString());
        output.put("reviewId", reviewId.toString());
        output.put("reviewStatus", reviewStatus);
        output.put("score", score);
        output.put("message", message);
        output.put("checks", checks);
        output.put("soundTimeline", soundTimeline);
        output.put("take", toTakeResponse(take.id()));
        generationJobService.completeGenerationJob(jobId, output);
        return toTakeResponse(take.id());
    }

    @Transactional
    public ShotTakeResponse confirmTake(UUID takeId, ShotTakeConfirmRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        boolean accepted = request != null && Boolean.TRUE.equals(request.accepted());
        jdbcTemplate.update(
                """
                update creator_shot_takes
                set status = ?,
                    review_status = ?,
                    accepted = ?,
                    user_notes = ?,
                    updated_at = now()
                where id = ?
                """,
                accepted ? "ACCEPTED" : "NEEDS_RESHOOT",
                accepted ? "CONFIRMED" : "REJECTED",
                accepted,
                defaultString(request == null ? null : request.note(), take.userNotes()),
                take.id()
        );
        return toTakeResponse(take.id());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAcceptedSequencePreview(UUID scriptId, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<ShotTakeData> acceptedTakes = loadAcceptedTakes(script.getId(), tenantId, userId);
        return latestAcceptedSequencePreview(script.getId(), acceptedTakes, tenantId, userId);
    }

    @Transactional
    public CreatorGenerationJob startAcceptedSequenceRenderJob(UUID scriptId, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<ShotTakeData> acceptedTakes = loadAcceptedTakes(script.getId(), tenantId, userId);
        if (acceptedTakes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accept at least one shot take before rendering the final sequence preview.");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scriptId", script.getId().toString());
        input.put("mode", "ACCEPTED_SHOT_SEQUENCE_PREVIEW");
        input.put("provider", "local_ffmpeg");
        input.put("model", "ffmpeg-accepted-shot-sequence-v1");
        input.put("acceptedTakeCount", acceptedTakes.size());
        input.put("shotNumbers", acceptedTakes.stream().map(ShotTakeData::shotNumber).toList());
        input.put("takeIds", acceptedTakes.stream().map(take -> take.id().toString()).toList());
        return generationJobService.startGenerationJob(
                "SHOT_TAKE_ACCEPTED_SEQUENCE_RENDER",
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                input
        );
    }

    public Map<String, Object> runAcceptedSequenceRenderJob(UUID jobId, UUID scriptId, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<ShotTakeData> acceptedTakes = loadAcceptedTakes(script.getId(), tenantId, userId);
        if (acceptedTakes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accept at least one shot take before rendering the final sequence preview.");
        }
        List<LocalShotSequenceRenderService.SequenceClip> clips = acceptedTakes.stream()
                .map(this::sequenceClipForAcceptedTake)
                .toList();
        generationJobService.updateGenerationJobProgress(
                jobId,
                25,
                "Preparing accepted shots for final mobile preview.",
                Map.of("scriptId", script.getId().toString(), "acceptedTakeCount", acceptedTakes.size(), "shotNumbers", acceptedTakes.stream().map(ShotTakeData::shotNumber).toList())
        );

        LocalShotSequenceRenderService.RenderedSequence renderedSequence = localShotSequenceRenderService.render(script.getId(), clips);
        generationJobService.updateGenerationJobProgress(
                jobId,
                82,
                "Saving final accepted-shot preview.",
                Map.of("scriptId", script.getId().toString(), "acceptedTakeCount", acceptedTakes.size())
        );

        String objectKey = "shot-takes-final-sequence/%s/accepted-preview.mp4".formatted(script.getId());
        AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAsset(
                objectKey,
                renderedSequence.bytes(),
                renderedSequence.contentType(),
                SIGNED_URL_TTL
        );
        Map<String, Object> metadata = new LinkedHashMap<>(renderedSequence.metadata());
        metadata.put("assetKind", "accepted_shot_sequence_preview");
        metadata.put("scriptId", script.getId().toString());
        metadata.put("acceptedTakeCount", acceptedTakes.size());
        metadata.put("shotNumbers", acceptedTakes.stream().map(ShotTakeData::shotNumber).toList());
        metadata.put("takeIds", acceptedTakes.stream().map(take -> take.id().toString()).toList());
        metadata.put("generationJobId", jobId.toString());

        CreatorAsset sequenceAsset = upsertAsset(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_SHOT_TAKE_FINAL_SEQUENCE_VIDEO)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> output = acceptedSequencePreviewFromAsset(sequenceAsset, metadata, acceptedTakes);
        output.put("status", "FINAL_SEQUENCE_READY");
        output.put("message", "Accepted-shot mobile preview is ready.");
        generationJobService.completeGenerationJob(jobId, output);
        return output;
    }

    @Transactional
    public CreatorGenerationJob startEnhancementPreviewJob(UUID takeId, ShotTakeEnhancePreviewRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        UUID variantId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("takeId", take.id().toString());
        input.put("variantId", variantId.toString());
        input.put("scriptId", take.scriptId().toString());
        input.put("shotNumber", take.shotNumber());
        input.put("editNote", defaultString(request == null ? null : request.editNote(), ""));
        input.put("generateImage", request == null || request.generateImage() == null || Boolean.TRUE.equals(request.generateImage()));
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                "SHOT_TAKE_ENHANCE_PREVIEW",
                take.tenantId(),
                take.userId(),
                take.projectId(),
                input
        );
        jdbcTemplate.update(
                """
                insert into creator_shot_enhancement_variants (
                    id, take_id, generation_job_id, status, provider, prompt_payload, created_at, updated_at
                ) values (?, ?, ?, 'PENDING', 'gemini_image', '{}'::jsonb, now(), now())
                """,
                variantId,
                take.id(),
                job.getId()
        );
        return job;
    }

    @Transactional
    public ShotTakeResponse runEnhancementPreviewJob(UUID jobId, UUID takeId, ShotTakeEnhancePreviewRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        UUID variantId = uuidValue(takeJobInput(jobId).get("variantId"));
        if (variantId == null) {
            variantId = latestVariantIdForJob(jobId);
        }
        CreatorScript script = loadScript(take.scriptId(), tenantId, userId);
        CreatorScriptShotPlan plan = loadShotPlan(script.getId(), take.shotNumber());
        ContinuityReferenceImage continuityReference = resolveContinuityReferenceImage(script.getId(), take, variantId);
        Map<String, Object> promptPayload = buildEnhancementPromptPayload(script, plan, take, request, continuityReference);
        UUID previewAssetId = null;
        String status = "PROMPT_READY";
        String message = "Image-wise polish prompt prepared. Upload a still/reference frame for Gemini image editing.";
        Map<String, Object> generatedCostMetadata = new LinkedHashMap<>();
        Map<String, Object> generatedTokenMetadata = new LinkedHashMap<>();

        boolean shouldGenerate = request == null || request.generateImage() == null || Boolean.TRUE.equals(request.generateImage());
        ReferenceImage referenceImage = resolveReferenceImage(take);
        if (shouldGenerate && referenceImage != null) {
            byte[] referenceBytes = assetStorageService.s3Client()
                    .getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(referenceImage.bucket())
                            .key(referenceImage.objectKey())
                            .build())
                    .asByteArray();
            List<StoryboardImageGenerationService.ReferenceImageInput> referenceInputs = new ArrayList<>();
            referenceInputs.add(new StoryboardImageGenerationService.ReferenceImageInput(
                    referenceBytes,
                    referenceImage.contentType(),
                    "current_frame_source_of_truth"
            ));
            boolean continuityReferenceUsed = addContinuityReferenceInput(referenceInputs, continuityReference);
            String primaryImagePrompt = stringValue(promptPayload.get("imagePrompt"), String.valueOf(promptPayload.get("prompt")));
            String retryImagePrompt = stringValue(promptPayload.get("safeRetryImagePrompt"), primaryImagePrompt);
            boolean retryUsed = false;
            String primaryFailure = "";
            StoryboardImageGenerationService.GeneratedImage generatedImage;
            try {
                generatedImage = imageGenerationService.generateImageFromReferences(
                        primaryImagePrompt,
                        referenceInputs,
                        stringValue(script.getScreenType(), "vertical")
                );
            } catch (IllegalStateException ex) {
                if (!isGeminiNoImageFailure(ex) || retryImagePrompt.equals(primaryImagePrompt)) {
                    throw ex;
                }
                retryUsed = true;
                primaryFailure = shortText(ex.getMessage(), 1200);
                log.warn("Gemini image preview returned no image; retrying with safe compact prompt jobId={} takeId={} reason={}", jobId, take.id(), primaryFailure);
                generatedImage = imageGenerationService.generateImageFromReferences(
                        retryImagePrompt,
                        referenceInputs,
                        stringValue(script.getScreenType(), "vertical")
                );
            }
            String objectKey = "shot-enhancements/%s/%02d/previews/%s.png".formatted(script.getId(), take.shotNumber(), variantId);
            publishProviderUsageFromMetadata(
                    "SHOT_TAKE_ENHANCE_PREVIEW",
                    generatedImage.metadata(),
                    take,
                    jobId,
                    "Generated shot enhancement preview"
            );
            AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAsset(
                    objectKey,
                    generatedImage.bytes(),
                    generatedImage.contentType(),
                    SIGNED_URL_TTL
            );
            Map<String, Object> metadata = new LinkedHashMap<>(generatedImage.metadata());
            metadata.put("scriptId", script.getId().toString());
            metadata.put("shotNumber", take.shotNumber());
            metadata.put("takeId", take.id().toString());
            metadata.put("variantId", variantId.toString());
            metadata.put("sourceAssetId", referenceImage.assetId().toString());
            metadata.put("sourceAssetRole", referenceImage.role());
            metadata.put("continuityReferenceUsed", continuityReferenceUsed);
            if (continuityReference != null) {
                metadata.put("continuityVariantId", continuityReference.variantId().toString());
                metadata.put("continuityTakeId", continuityReference.takeId().toString());
                metadata.put("continuityShotNumber", continuityReference.shotNumber());
                metadata.put("continuityAssetId", continuityReference.referenceImage().assetId().toString());
            }
            metadata.put("imageKind", "shot_enhancement_preview");
            metadata.put("retryUsed", retryUsed);
            if (!primaryFailure.isBlank()) {
                metadata.put("primaryFailure", primaryFailure);
            }
            generatedCostMetadata = objectMap(metadata.get("costMetadata"));
            generatedTokenMetadata = objectMap(metadata.get("tokenMetadata"));
            CreatorAsset previewAsset = upsertAsset(CreatorAsset.builder()
                    .tenantId(take.tenantId())
                    .userId(take.userId())
                    .projectId(take.projectId())
                    .assetType(ASSET_TYPE_SHOT_ENHANCEMENT_PREVIEW)
                    .bucket(stored.bucket())
                    .objectKey(stored.objectKey())
                    .contentType(stored.contentType())
                    .sizeBytes(stored.sizeBytes())
                    .publicUrl(stored.signedUrl())
                    .metadata(metadata)
                    .build());
            previewAssetId = previewAsset.getId();
            status = "PREVIEW_READY";
            message = "Gemini image-wise polish preview generated. Confirm or request edits before applying the look.";
        } else if (shouldGenerate) {
            status = "REFERENCE_FRAME_REQUIRED";
            message = "Upload an image take or add a still frame from the video so Gemini can edit the real frame.";
        }

        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = ?,
                    preview_asset_id = ?,
                    provider = 'gemini_image',
                    prompt_payload = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                status,
                previewAssetId,
                writeJson(promptPayload),
                variantId
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("takeId", take.id().toString());
        output.put("variantId", variantId.toString());
        output.put("previewAssetId", previewAssetId == null ? null : previewAssetId.toString());
        output.put("status", status);
        output.put("message", message);
        output.put("promptPayload", promptPayload);
        output.put("take", toTakeResponse(take.id()));
        if (!generatedCostMetadata.isEmpty()) {
            output.put("costMetadata", generatedCostMetadata);
        }
        if (!generatedTokenMetadata.isEmpty()) {
            output.put("tokenMetadata", generatedTokenMetadata);
        }
        generationJobService.completeGenerationJob(jobId, output);
        return toTakeResponse(take.id());
    }

    @Transactional
    public ShotTakeResponse saveEnhancementFeedback(UUID takeId, ShotTakeEnhanceFeedbackRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        UUID variantId = latestVariantIdForTake(take.id());
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generate a polish preview before adding feedback.");
        }
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'EDITS_REQUESTED',
                    user_feedback = ?,
                    updated_at = now()
                where id = ?
                """,
                defaultString(request == null ? null : request.feedback(), ""),
                variantId
        );
        return toTakeResponse(take.id());
    }

    @Transactional
    public ShotTakeResponse applyEnhancementPreviewToTimeline(UUID takeId, UUID variantId, boolean applied, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        Map<String, Object> variantRow = jdbcTemplate.query(
                """
                select id, preview_asset_id
                from creator_shot_enhancement_variants
                where id = ?
                  and take_id = ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("previewAssetId", rs.getObject("preview_asset_id", UUID.class));
                    return row;
                },
                variantId,
                take.id()
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Enhancement preview variant was not found for this take."));
        if (variantRow.get("previewAssetId") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generate an enhanced image preview before applying it to the timeline.");
        }

        if (applied) {
            jdbcTemplate.update(
                    """
                    update creator_shot_enhancement_variants
                    set status = case when status = 'TIMELINE_APPLIED' then 'PREVIEW_READY' else status end,
                        prompt_payload = prompt_payload #- '{timelineClip}',
                        updated_at = now()
                    where take_id = ?
                      and id <> ?
                    """,
                    take.id(),
                    variantId
            );
            Map<String, Object> timelineClip = new LinkedHashMap<>();
            timelineClip.put("applied", true);
            timelineClip.put("clipType", "ENHANCED_IMAGE");
            timelineClip.put("takeId", take.id().toString());
            timelineClip.put("variantId", variantId.toString());
            timelineClip.put("shotNumber", take.shotNumber());
            timelineClip.put("appliedAt", OffsetDateTime.now().toString());
            timelineClip.put("durationPolicy", "SPAN_FULL_SHOT_UNTIL_FINAL_VIDEO_RENDER");
            jdbcTemplate.update(
                    """
                    update creator_shot_enhancement_variants
                    set status = 'TIMELINE_APPLIED',
                        prompt_payload = jsonb_set(prompt_payload, '{timelineClip}', cast(? as jsonb), true),
                        updated_at = now()
                    where id = ?
                      and take_id = ?
                    """,
                    writeJson(timelineClip),
                    variantId,
                    take.id()
            );
        } else {
            jdbcTemplate.update(
                    """
                    update creator_shot_enhancement_variants
                    set status = case when status = 'TIMELINE_APPLIED' then 'PREVIEW_READY' else status end,
                        prompt_payload = prompt_payload #- '{timelineClip}',
                        updated_at = now()
                    where id = ?
                      and take_id = ?
                    """,
                    variantId,
                    take.id()
            );
        }
        return toTakeResponse(take.id());
    }

    @Transactional
    public CreatorGenerationJob startStudioPolishJob(UUID takeId, ShotTakeStudioPolishRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        if (!isVideoContentType(take.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Studio Polish expects a short video take. Use Gemini frame preview for still images.");
        }
        String provider = resolveStudioPolishVideoProvider(request);
        providerCreditHealthService.requirePolishProviderReady(provider);
        String model = resolveStudioPolishVideoModel(provider, request);
        String providerMode = resolveStudioPolishVideoMode(provider, request);
        Long providerSeed = resolveStudioPolishProviderSeed(provider, take.id(), request);
        String preset = resolveStudioPolishPreset(request);
        Map<String, Object> studioPolishControls = resolveStudioPolishControls(request, preset);
        String idempotencyKey = studioPolishIdempotencyKey(
                "SHOT_TAKE_STUDIO_POLISH",
                take.id(),
                provider,
                model,
                providerMode,
                providerSeed,
                preset,
                studioPolishControls,
                request
        );
        log.info(
                "Studio Polish request resolved jobType=SHOT_TAKE_STUDIO_POLISH takeId={} scriptId={} shotNumber={} provider={} model={} providerMode={} seed={} preset={} editNoteChars={} idempotencyKey={}",
                take.id(),
                take.scriptId(),
                take.shotNumber(),
                provider,
                model,
                defaultString(providerMode, ""),
                providerSeed,
                preset,
                defaultString(request == null ? null : request.editNote(), "").length(),
                shortText(idempotencyKey, 72)
        );
        CreatorGenerationJob existingJob = findActiveStudioPolishJob(
                take.tenantId(),
                take.userId(),
                "SHOT_TAKE_STUDIO_POLISH",
                idempotencyKey
        );
        if (existingJob != null) {
            log.info(
                    "Studio Polish idempotency reuse jobType=SHOT_TAKE_STUDIO_POLISH existingJobId={} status={} takeId={} provider={} model={} seed={} idempotencyKey={}",
                    existingJob.getId(),
                    existingJob.getStatus(),
                    take.id(),
                    provider,
                    model,
                    providerSeed,
                    shortText(idempotencyKey, 72)
            );
            return existingJob;
        }
        UUID variantId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("takeId", take.id().toString());
        input.put("variantId", variantId.toString());
        input.put("scriptId", take.scriptId().toString());
        input.put("shotNumber", take.shotNumber());
        input.put("mode", "STUDIO_POLISH_VIDEO_TO_VIDEO");
        input.put("provider", provider);
        input.put("model", model);
        input.put("providerMode", providerMode);
        input.put("idempotencyKey", idempotencyKey);
        if (providerSeed != null) {
            input.put("seed", providerSeed);
            input.put("seedPolicy", "runway_seed_is_stable_for_progressive_refinement");
        }
        input.put("resolution", properties.getAi().getGeminiVideoResolution());
        input.put("durationSeconds", properties.getAi().getGeminiVideoDurationSeconds());
        input.put("nativeAudioRequested", "google_veo".equals(provider));
        input.put("audioPolicy", "video_provider_visual_only_audio_cleanup_and_mix_are_separate");
        input.put("editNote", defaultString(request == null ? null : request.editNote(), ""));
        input.put("preset", preset);
        input.put("studioPolishControls", studioPolishControls);
        input.put("promptPolicy", "production_design_plus_studio_look_user_prompt_overrides_conflicting_details_only");
        CreatorGenerationJob job;
        try {
            job = generationJobService.startGenerationJob(
                    "SHOT_TAKE_STUDIO_POLISH",
                    take.tenantId(),
                    take.userId(),
                    take.projectId(),
                    input
            );
        } catch (DataIntegrityViolationException ex) {
            CreatorGenerationJob duplicateJob = findActiveStudioPolishJob(
                    take.tenantId(),
                    take.userId(),
                    "SHOT_TAKE_STUDIO_POLISH",
                    idempotencyKey
            );
            if (duplicateJob != null) {
                log.info(
                        "Studio Polish idempotency duplicate recovered jobType=SHOT_TAKE_STUDIO_POLISH existingJobId={} status={} takeId={} provider={} model={} seed={} idempotencyKey={}",
                        duplicateJob.getId(),
                        duplicateJob.getStatus(),
                        take.id(),
                        provider,
                        model,
                        providerSeed,
                        shortText(idempotencyKey, 72)
                );
                return duplicateJob;
            }
            throw ex;
        }
        jdbcTemplate.update(
                """
                insert into creator_shot_enhancement_variants (
                    id, take_id, generation_job_id, status, provider, prompt_payload, created_at, updated_at
                ) values (?, ?, ?, 'VIDEO_PENDING', ?, '{}'::jsonb, now(), now())
                """,
                variantId,
                take.id(),
                job.getId(),
                provider
        );
        log.info(
                "Studio Polish job created jobType=SHOT_TAKE_STUDIO_POLISH jobId={} variantId={} takeId={} scriptId={} shotNumber={} provider={} model={} providerMode={} seed={} preset={}",
                job.getId(),
                variantId,
                take.id(),
                take.scriptId(),
                take.shotNumber(),
                provider,
                model,
                defaultString(providerMode, ""),
                providerSeed,
                preset
        );
        return job;
    }

    @Transactional
    public CreatorGenerationJob startAudioEnhancementJob(UUID takeId, ShotTakeAudioEnhanceRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        AudioEnhancementSource source = resolveAudioEnhancementSource(take, request);
        if (!isVideoContentType(source.contentType()) && !isAudioContentType(source.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio enhancement expects an uploaded video or audio take.");
        }
        UUID variantId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("takeId", take.id().toString());
        input.put("variantId", variantId.toString());
        input.put("scriptId", take.scriptId().toString());
        input.put("shotNumber", take.shotNumber());
        input.put("mode", "AUDIO_ENHANCE_PRESERVE_TEXTURE");
        input.put("purpose", "dialogue_cleanup_voice_clarity");
        input.put("provider", resolveAudioEnhancementProvider(request));
        input.put("model", resolveAudioEnhancementModel(request));
        input.put("sourceAssetId", source.assetId().toString());
        input.put("sourceAssetType", source.assetType());
        input.put("sourceRole", source.role());
        input.put("usesLyria", false);
        input.put("musicGenerationAllowed", false);
        input.put("preserveVoiceTexture", request == null || request.preserveVoiceTexture() == null || Boolean.TRUE.equals(request.preserveVoiceTexture()));
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                "SHOT_TAKE_AUDIO_ENHANCE",
                take.tenantId(),
                take.userId(),
                take.projectId(),
                input
        );
        jdbcTemplate.update(
                """
                insert into creator_shot_enhancement_variants (
                    id, take_id, generation_job_id, status, provider, prompt_payload, created_at, updated_at
                ) values (?, ?, ?, 'AUDIO_ENHANCE_PENDING', ?, '{}'::jsonb, now(), now())
                """,
                variantId,
                take.id(),
                job.getId(),
                resolveAudioEnhancementProvider(request)
        );
        return job;
    }

    @Transactional
    public ShotTakeResponse runAudioEnhancementJob(UUID jobId, UUID takeId, ShotTakeAudioEnhanceRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        CreatorScript script = loadScript(take.scriptId(), tenantId, userId);
        CreatorScriptShotPlan plan = loadShotPlan(script.getId(), take.shotNumber());
        UUID variantId = uuidValue(takeJobInput(jobId).get("variantId"));
        if (variantId == null) {
            variantId = latestVariantIdForJob(jobId);
        }
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Audio enhancement variant was not created.");
        }

        AudioEnhancementSource source = resolveAudioEnhancementSource(take, request);
        Map<String, Object> audioPayload = buildAudioEnhancementPayload(script, plan, take, source, request);
        String provider = resolveAudioEnhancementProvider(request);
        String model = resolveAudioEnhancementModel(request);
        Map<String, Object> costMetadata = objectMap(audioPayload.get("costMetadata"));

        if ("local_ffmpeg".equalsIgnoreCase(provider)) {
            String message = "Enhancing recorded audio locally with FFmpeg while preserving voice texture.";
            jdbcTemplate.update(
                    """
                    update creator_shot_enhancement_variants
                    set status = 'AUDIO_ENHANCE_RENDERING',
                        provider = ?,
                        prompt_payload = cast(? as jsonb),
                        provider_request = cast(? as jsonb),
                        provider_response = cast(? as jsonb),
                        updated_at = now()
                    where id = ?
                    """,
                    provider,
                    writeJson(audioPayload),
                    writeJson(audioPayload.get("providerTask")),
                    writeJson(Map.of("status", "AUDIO_ENHANCE_RENDERING", "message", message)),
                    variantId
            );
            Map<String, Object> progressOutput = new LinkedHashMap<>();
            progressOutput.put("takeId", take.id().toString());
            progressOutput.put("variantId", variantId.toString());
            progressOutput.put("status", "AUDIO_ENHANCE_RENDERING");
            progressOutput.put("message", message);
            progressOutput.put("provider", provider);
            progressOutput.put("model", model);
            progressOutput.put("durationSeconds", audioPayload.get("durationSeconds"));
            progressOutput.put("providerTask", audioPayload.get("providerTask"));
            progressOutput.put("audioEnhancement", audioPayload);
            progressOutput.put("take", toTakeResponse(take.id()));
            generationJobService.updateGenerationJobProgress(jobId, 55, message, progressOutput);

            LocalAudioEnhancementService.RenderedAudio renderedAudio = localAudioEnhancementService.render(
                    script.getId(),
                    take.shotNumber(),
                    take.id(),
                    source.bucket(),
                    source.objectKey(),
                    source.contentType(),
                    objectMap(audioPayload.get("controls"))
            );
            Map<String, Object> metadata = new LinkedHashMap<>(renderedAudio.metadata());
            metadata.put("providerTask", audioPayload.get("providerTask"));
            metadata.put("costMetadata", costMetadata);
            metadata.put("sourceAssetId", source.assetId().toString());
            metadata.put("sourceAssetType", source.assetType());
            metadata.put("sourceRole", source.role());
            metadata.put("sourceMemory", audioPayload.get("sourceMemory"));
            return completeAudioEnhancement(
                    take.id(),
                    variantId,
                    new GeneratedMultipartFile(renderedAudio.filename(), renderedAudio.contentType(), renderedAudio.bytes()),
                    writeJson(metadata),
                    tenantId,
                    userId
            );
        }

        String status = "AUDIO_ENHANCE_TASK_READY";
        String message = "Audio enhancement request prepared. Waiting for provider/worker to upload the cleaned audio.";

        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = ?,
                    provider = ?,
                    prompt_payload = cast(? as jsonb),
                    provider_request = cast(? as jsonb),
                    provider_response = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                status,
                provider,
                writeJson(audioPayload),
                writeJson(audioPayload.get("providerTask")),
                writeJson(Map.of("status", status, "message", message)),
                variantId
        );

        creatorAiService.publishProviderUsageDebit(
                "SHOT_TAKE_AUDIO_ENHANCE",
                provider,
                model,
                costMetadata,
                new CreatorAiService.AiUsageContext(take.tenantId(), take.userId(), take.projectId(), jobId, null),
                "Creator shot audio cleanup provider usage"
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("takeId", take.id().toString());
        output.put("variantId", variantId.toString());
        output.put("status", status);
        output.put("message", message);
        output.put("provider", provider);
        output.put("model", model);
        output.put("durationSeconds", audioPayload.get("durationSeconds"));
        output.put("costMetadata", costMetadata);
        output.put("providerTask", audioPayload.get("providerTask"));
        output.put("audioEnhancement", audioPayload);
        output.put("take", toTakeResponse(take.id()));
        generationJobService.updateGenerationJobProgress(jobId, 40, message, output);
        return toTakeResponse(take.id());
    }

    @Transactional
    public CreatorGenerationJob startAudioMixJob(UUID takeId, ShotTakeAudioMixRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        if (!isVideoContentType(take.contentType()) && !isAudioContentType(take.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio mix expects an uploaded video or audio take.");
        }

        Map<String, Object> analysis = loadTakeMediaAnalysis(take.id());
        Map<String, Object> timeline = normalizeSoundTimeline(
                request == null ? null : request.layers(),
                request == null ? null : request.mixSettings(),
                analysis.get("soundTimeline")
        );
        analysis.put("soundTimeline", timeline);
        analysis.put("audioMix", timeline.get("mixSettings"));
        analysis.put("updatedAt", OffsetDateTime.now().toString());
        saveTakeMediaAnalysis(take.id(), analysis);

        UUID variantId = UUID.randomUUID();
        Map<String, Object> mixSettings = objectMap(timeline.get("mixSettings"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("takeId", take.id().toString());
        input.put("variantId", variantId.toString());
        input.put("scriptId", take.scriptId().toString());
        input.put("shotNumber", take.shotNumber());
        input.put("mode", "AUDIO_MIX_RENDER");
        input.put("renderMode", defaultString(request == null ? null : request.renderMode(), "polished_timeline_audio"));
        input.put("provider", resolveAudioMixProvider(request));
        input.put("model", resolveAudioMixModel(request));
        input.put("timelineLayerCount", soundLayerList(timeline.get("layers")).size());
        input.put("volumeAutomationCount", volumeAutomationList(mixSettings.get("volumeAutomation")).size());
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                "SHOT_TAKE_AUDIO_MIX",
                take.tenantId(),
                take.userId(),
                take.projectId(),
                input
        );
        jdbcTemplate.update(
                """
                insert into creator_shot_enhancement_variants (
                    id, take_id, generation_job_id, status, provider, prompt_payload, created_at, updated_at
                ) values (?, ?, ?, 'AUDIO_MIX_PENDING', ?, '{}'::jsonb, now(), now())
                """,
                variantId,
                take.id(),
                job.getId(),
                resolveAudioMixProvider(request)
        );
        return job;
    }

    @Transactional
    public ShotTakeResponse runAudioMixJob(UUID jobId, UUID takeId, ShotTakeAudioMixRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        CreatorScript script = loadScript(take.scriptId(), tenantId, userId);
        CreatorScriptShotPlan plan = loadShotPlan(script.getId(), take.shotNumber());
        UUID variantId = uuidValue(takeJobInput(jobId).get("variantId"));
        if (variantId == null) {
            variantId = latestVariantIdForJob(jobId);
        }
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Audio mix variant was not created.");
        }

        Map<String, Object> audioPayload = buildAudioMixPayload(script, plan, take, request, variantId);
        String provider = resolveAudioMixProvider(request);
        String model = resolveAudioMixModel(request);
        Map<String, Object> providerTask = objectMap(audioPayload.get("providerTask"));

        if ("local_ffmpeg".equalsIgnoreCase(provider)) {
            String message = "Rendering polished timeline audio with FFmpeg.";
            jdbcTemplate.update(
                    """
                    update creator_shot_enhancement_variants
                    set status = 'AUDIO_MIX_RENDERING',
                        provider = ?,
                        prompt_payload = cast(? as jsonb),
                        provider_request = cast(? as jsonb),
                        provider_response = cast(? as jsonb),
                        updated_at = now()
                    where id = ?
                    """,
                    provider,
                    writeJson(audioPayload),
                    writeJson(providerTask),
                    writeJson(Map.of("status", "AUDIO_MIX_RENDERING", "message", message)),
                    variantId
            );
            Map<String, Object> progressOutput = new LinkedHashMap<>();
            progressOutput.put("takeId", take.id().toString());
            progressOutput.put("variantId", variantId.toString());
            progressOutput.put("status", "AUDIO_MIX_RENDERING");
            progressOutput.put("message", message);
            progressOutput.put("provider", provider);
            progressOutput.put("model", model);
            progressOutput.put("durationSeconds", audioPayload.get("durationSeconds"));
            progressOutput.put("providerTask", providerTask);
            progressOutput.put("audioMix", audioPayload);
            progressOutput.put("take", toTakeResponse(take.id()));
            generationJobService.updateGenerationJobProgress(jobId, 55, message, progressOutput);

            Map<String, Object> userSoundTimeline = objectMap(audioPayload.get("userSoundTimeline"));
            List<Map<String, Object>> userSoundLayers = soundLayerList(userSoundTimeline.get("layers"));
            Map<String, Object> mixSettings = objectMap(audioPayload.get("mixSettings"));
            LocalAudioMixService.RenderedAudio renderedAudio = localAudioMixService.render(
                    take.tenantId(),
                    take.userId(),
                    script.getId(),
                    take.shotNumber(),
                    take.id(),
                    take.bucket(),
                    take.objectKey(),
                    take.contentType(),
                    userSoundLayers,
                    mixSettings
            );
            Map<String, Object> metadata = new LinkedHashMap<>(renderedAudio.metadata());
            metadata.put("providerTask", providerTask);
            metadata.put("mixSettings", mixSettings);
            return completeAudioMix(
                    take.id(),
                    variantId,
                    new GeneratedMultipartFile(renderedAudio.filename(), renderedAudio.contentType(), renderedAudio.bytes()),
                    writeJson(metadata),
                    tenantId,
                    userId
            );
        }

        String status = "AUDIO_MIX_TASK_READY";
        String message = "Audio mix request prepared. Waiting for the audio worker to upload the rendered mix.";

        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = ?,
                    provider = ?,
                    prompt_payload = cast(? as jsonb),
                    provider_request = cast(? as jsonb),
                    provider_response = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                status,
                provider,
                writeJson(audioPayload),
                writeJson(providerTask),
                writeJson(Map.of("status", status, "message", message)),
                variantId
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("takeId", take.id().toString());
        output.put("variantId", variantId.toString());
        output.put("status", status);
        output.put("message", message);
        output.put("provider", provider);
        output.put("model", model);
        output.put("durationSeconds", audioPayload.get("durationSeconds"));
        output.put("providerTask", providerTask);
        output.put("audioMix", audioPayload);
        output.put("take", toTakeResponse(take.id()));
        generationJobService.updateGenerationJobProgress(jobId, 45, message, output);
        return toTakeResponse(take.id());
    }

    @Transactional
    public CreatorGenerationJob startStudioPolishAllJob(UUID scriptId, ShotTakeStudioPolishRequest request, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<ShotTakeData> acceptedTakes = loadAcceptedTakes(script.getId(), tenantId, userId).stream()
                .filter(take -> isVideoContentType(take.contentType()))
                .toList();
        if (acceptedTakes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accept at least one video take before starting Studio Polish.");
        }
        String provider = resolveStudioPolishVideoProvider(request);
        providerCreditHealthService.requirePolishProviderReady(provider);
        String model = resolveStudioPolishVideoModel(provider, request);
        String providerMode = resolveStudioPolishVideoMode(provider, request);
        Long providerSeed = resolveStudioPolishProviderSeed(provider, script.getId(), request);
        String preset = resolveStudioPolishPreset(request);
        Map<String, Object> studioPolishControls = resolveStudioPolishControls(request, preset);
        String idempotencyKey = studioPolishIdempotencyKey(
                "SHOT_TAKE_STUDIO_POLISH_ALL",
                script.getId(),
                provider,
                model,
                providerMode,
                providerSeed,
                preset,
                studioPolishControls,
                request
        );
        log.info(
                "Studio Polish request resolved jobType=SHOT_TAKE_STUDIO_POLISH_ALL scriptId={} acceptedTakeCount={} shotNumbers={} provider={} model={} providerMode={} seed={} preset={} editNoteChars={} idempotencyKey={}",
                script.getId(),
                acceptedTakes.size(),
                acceptedTakes.stream().map(ShotTakeData::shotNumber).toList(),
                provider,
                model,
                defaultString(providerMode, ""),
                providerSeed,
                preset,
                defaultString(request == null ? null : request.editNote(), "").length(),
                shortText(idempotencyKey, 72)
        );
        CreatorGenerationJob existingJob = findActiveStudioPolishJob(
                script.getTenantId(),
                script.getUserId(),
                "SHOT_TAKE_STUDIO_POLISH_ALL",
                idempotencyKey
        );
        if (existingJob != null) {
            log.info(
                    "Studio Polish idempotency reuse jobType=SHOT_TAKE_STUDIO_POLISH_ALL existingJobId={} status={} scriptId={} provider={} model={} seed={} idempotencyKey={}",
                    existingJob.getId(),
                    existingJob.getStatus(),
                    script.getId(),
                    provider,
                    model,
                    providerSeed,
                    shortText(idempotencyKey, 72)
            );
            return existingJob;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scriptId", script.getId().toString());
        input.put("mode", "STUDIO_POLISH_VIDEO_TO_VIDEO_ALL");
        input.put("provider", provider);
        input.put("model", model);
        input.put("providerMode", providerMode);
        input.put("idempotencyKey", idempotencyKey);
        if (providerSeed != null) {
            input.put("seed", providerSeed);
            input.put("seedPolicy", "runway_seed_is_stable_for_progressive_refinement");
        }
        input.put("resolution", properties.getAi().getGeminiVideoResolution());
        input.put("durationSeconds", properties.getAi().getGeminiVideoDurationSeconds());
        input.put("nativeAudioRequested", "google_veo".equals(provider));
        input.put("audioPolicy", "video_provider_visual_only_audio_cleanup_and_mix_are_separate");
        input.put("acceptedTakeCount", acceptedTakes.size());
        input.put("shotNumbers", acceptedTakes.stream().map(ShotTakeData::shotNumber).toList());
        input.put("editNote", defaultString(request == null ? null : request.editNote(), ""));
        input.put("preset", preset);
        input.put("studioPolishControls", studioPolishControls);
        input.put("promptPolicy", "production_design_plus_studio_look_plus_optional_user_prompt");
        try {
            CreatorGenerationJob job = generationJobService.startGenerationJob(
                    "SHOT_TAKE_STUDIO_POLISH_ALL",
                    script.getTenantId(),
                    script.getUserId(),
                    script.getProjectId(),
                    input
            );
            log.info(
                    "Studio Polish job created jobType=SHOT_TAKE_STUDIO_POLISH_ALL jobId={} scriptId={} acceptedTakeCount={} provider={} model={} providerMode={} seed={} preset={}",
                    job.getId(),
                    script.getId(),
                    acceptedTakes.size(),
                    provider,
                    model,
                    defaultString(providerMode, ""),
                    providerSeed,
                    preset
            );
            return job;
        } catch (DataIntegrityViolationException ex) {
            CreatorGenerationJob duplicateJob = findActiveStudioPolishJob(
                    script.getTenantId(),
                    script.getUserId(),
                    "SHOT_TAKE_STUDIO_POLISH_ALL",
                    idempotencyKey
            );
            if (duplicateJob != null) {
                log.info(
                        "Studio Polish idempotency duplicate recovered jobType=SHOT_TAKE_STUDIO_POLISH_ALL existingJobId={} status={} scriptId={} provider={} model={} seed={} idempotencyKey={}",
                        duplicateJob.getId(),
                        duplicateJob.getStatus(),
                        script.getId(),
                        provider,
                        model,
                        providerSeed,
                        shortText(idempotencyKey, 72)
                );
                return duplicateJob;
            }
            throw ex;
        }
    }

    public ShotTakeResponse runStudioPolishJob(UUID jobId, UUID takeId, ShotTakeStudioPolishRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        CreatorScript script = loadScript(take.scriptId(), tenantId, userId);
        CreatorScriptShotPlan plan = loadShotPlan(script.getId(), take.shotNumber());
        UUID variantId = uuidValue(takeJobInput(jobId).get("variantId"));
        if (variantId == null) {
            variantId = latestVariantIdForJob(jobId);
        }
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Studio Polish variant was not created.");
        }

        String provider = resolveStudioPolishVideoProvider(request);
        String providerLabel = studioPolishProviderLabel(provider);
        providerCreditHealthService.requirePolishProviderReady(provider);
        ReferenceImage sourceImage = resolveStudioVeoSourceImage(take);
        Map<String, Object> studioPayload = buildStudioVeoPromptPayload(jobId, script, plan, take, request, sourceImage);
        String providerPrompt = firstText(studioPayload.get("providerPrompt"), studioPayload.get("prompt"));
        log.info(
                "Studio Polish payload ready jobId={} takeId={} variantId={} scriptId={} shotNumber={} provider={} providerLabel={} model={} providerMode={} seed={} referenceAssetId={} promptChars={} promptSnippet=\"{}\"",
                jobId,
                take.id(),
                variantId,
                script.getId(),
                take.shotNumber(),
                provider,
                providerLabel,
                stringValue(studioPayload.get("model"), ""),
                stringValue(studioPayload.get("providerMode"), ""),
                longValue(studioPayload.get("seed")),
                sourceImage == null ? null : sourceImage.assetId(),
                providerPrompt.length(),
                logSnippet(providerPrompt, 900)
        );
        if (sourceImage == null && studioPolishProviderRequiresReference(provider)) {
            String status = "REFERENCE_FRAME_REQUIRED";
            String message = "Choose a timeline frame from the uploaded video before " + providerLabel + " can render the final shot.";
            studioPayload.put("referenceFrameMissing", true);
            markVeoVariantReferenceRequired(variantId, studioPayload, message);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("takeId", take.id().toString());
            output.put("variantId", variantId.toString());
            output.put("status", status);
            output.put("message", message);
            output.put("studioPolish", studioPayload);
            output.put("take", toTakeResponse(take.id()));
            generationJobService.completeGenerationJob(jobId, output);
            return toTakeResponse(take.id());
        }

        if (!properties.getAi().isShotVideoGenerationEnabled()) {
            String message = providerLabel + " prompt prepared. Enable CREATOR_SHOT_VIDEO_GENERATION_ENABLED to render the final shot video.";
            markVeoVariantPromptReady(variantId, studioPayload);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("takeId", take.id().toString());
            output.put("variantId", variantId.toString());
            output.put("status", "VIDEO_PROMPT_READY");
            output.put("message", message);
            output.put("studioPolish", studioPayload);
            output.put("providerTask", studioPayload.get("providerTask"));
            output.put("take", toTakeResponse(take.id()));
            generationJobService.completeGenerationJob(jobId, output);
            return toTakeResponse(take.id());
        }

        try {
            markVeoVariantPromptReady(variantId, studioPayload);
            try (StudioPolishProviderQueueService.ProviderQueueLease queueLease = acquireStudioPolishProviderSlot(
                    provider,
                    providerLabel,
                    jobId,
                    variantId,
                    take,
                    studioPayload
            )) {
                studioPayload.put("providerQueue", queueLease.metadata());
                log.info(
                        "Studio Polish provider slot acquired jobId={} takeId={} variantId={} provider={} model={} seed={} queueMetadata={}",
                        jobId,
                        take.id(),
                        variantId,
                        provider,
                        stringValue(studioPayload.get("model"), ""),
                        longValue(studioPayload.get("seed")),
                        queueLease.metadata()
                );
                markVeoVariantRunning(variantId, studioPayload);
                generationJobService.updateGenerationJobProgress(
                        jobId,
                        35,
                        "Rendering " + providerLabel + " video for shot " + take.shotNumber(),
                        Map.of(
                                "takeId", take.id().toString(),
                                "variantId", variantId.toString(),
                                "studioPolish", studioPayload,
                                "providerQueue", queueLease.metadata()
                        )
                );
                StudioPolishVideoGenerationService.GeneratedVideo generatedVideo = generateStudioPolishVideo(script, take, sourceImage, studioPayload);
                log.info(
                        "Studio Polish provider output ready jobId={} takeId={} variantId={} provider={} model={} operationId={} bytes={} contentType={}",
                        jobId,
                        take.id(),
                        variantId,
                        provider,
                        stringValue(generatedVideo.metadata().get("model"), stringValue(studioPayload.get("model"), "")),
                        generatedVideo.operationName(),
                        generatedVideo.bytes() == null ? 0 : generatedVideo.bytes().length,
                        generatedVideo.contentType()
                );
                UUID finalVideoAssetId = storeVeoVideoAsset(script, take, variantId, sourceImage, generatedVideo);
                markVeoVariantReady(variantId, finalVideoAssetId, generatedVideo);
                publishProviderUsageFromMetadata(
                        "SHOT_TAKE_STUDIO_POLISH_" + provider.toUpperCase(Locale.ROOT),
                        generatedVideo.metadata(),
                        take,
                        jobId,
                        "Generated " + providerLabel + " Studio Polish shot video"
                );
                Map<String, Object> videoCostMetadata = objectMap(generatedVideo.metadata().get("costMetadata"));
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("takeId", take.id().toString());
                output.put("variantId", variantId.toString());
                output.put("status", "VIDEO_READY");
                output.put("message", providerLabel + " Studio Polish video is ready and active in the polished timeline.");
                output.put("finalVideoAssetId", finalVideoAssetId.toString());
                output.put("providerOperationId", generatedVideo.operationName());
                output.put("studioPolish", studioPayload);
                output.put("providerTask", studioPayload.get("providerTask"));
                output.put("providerQueue", queueLease.metadata());
                output.put("continuityContextForNextShot", studioPayload.get("continuityContextForNextShot"));
                output.put("take", toTakeResponse(take.id()));
                if (!videoCostMetadata.isEmpty()) {
                    output.put("costMetadata", videoCostMetadata);
                }
                generationJobService.completeGenerationJob(jobId, output);
            }
            return toTakeResponse(take.id());
        } catch (RuntimeException ex) {
            String message = defaultString(ex.getMessage(), ex.getClass().getSimpleName());
            log.error(
                    "Studio Polish job failed jobId={} takeId={} variantId={} provider={} model={} seed={} errorType={} errorMessage={}",
                    jobId,
                    take.id(),
                    variantId,
                    provider,
                    stringValue(studioPayload.get("model"), ""),
                    longValue(studioPayload.get("seed")),
                    ex.getClass().getSimpleName(),
                    message,
                    ex
            );
            markVeoVariantFailed(variantId, message);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("takeId", take.id().toString());
            output.put("variantId", variantId.toString());
            output.put("status", "FAILED");
            output.put("message", message);
            output.put("studioPolish", studioPayload);
            output.put("take", toTakeResponse(take.id()));
            generationJobService.failGenerationJob(jobId, message, output);
            throw ex;
        }
    }

    public void runStudioPolishAllJob(UUID jobId, UUID scriptId, ShotTakeStudioPolishRequest request, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<ShotTakeData> acceptedTakes = loadAcceptedTakes(script.getId(), tenantId, userId).stream()
                .filter(take -> isVideoContentType(take.contentType()))
                .toList();
        List<Map<String, Object>> shotOutputs = new ArrayList<>();
        List<Map<String, Object>> costMetadataItems = new ArrayList<>();
        int generatedCount = 0;
        int failedCount = 0;
        String provider = resolveStudioPolishVideoProvider(request);
        String providerLabel = studioPolishProviderLabel(provider);
        providerCreditHealthService.requirePolishProviderReady(provider);
        for (int index = 0; index < acceptedTakes.size(); index++) {
            ShotTakeData take = acceptedTakes.get(index);
            CreatorScriptShotPlan plan = loadShotPlan(script.getId(), take.shotNumber());
            ReferenceImage sourceImage = resolveStudioVeoSourceImage(take);
            Map<String, Object> studioPayload = buildStudioVeoPromptPayload(jobId, script, plan, take, request, sourceImage);
            UUID variantId = createVeoVariant(take.id(), jobId, studioPayload);
            String providerPrompt = firstText(studioPayload.get("providerPrompt"), studioPayload.get("prompt"));
            log.info(
                    "Studio Polish all-shots payload ready jobId={} scriptId={} shotIndex={} shotNumber={} takeId={} variantId={} provider={} model={} providerMode={} seed={} referenceAssetId={} promptChars={} promptSnippet=\"{}\"",
                    jobId,
                    script.getId(),
                    index + 1,
                    take.shotNumber(),
                    take.id(),
                    variantId,
                    provider,
                    stringValue(studioPayload.get("model"), ""),
                    stringValue(studioPayload.get("providerMode"), ""),
                    longValue(studioPayload.get("seed")),
                    sourceImage == null ? null : sourceImage.assetId(),
                    providerPrompt.length(),
                    logSnippet(providerPrompt, 700)
            );
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("takeId", take.id().toString());
            item.put("variantId", variantId.toString());
            item.put("shotNumber", take.shotNumber());
            item.put("provider", provider);
            item.put("model", resolveStudioPolishVideoModel(provider, request));
            item.put("referenceAssetId", sourceImage == null ? null : sourceImage.assetId().toString());

            if (sourceImage == null && studioPolishProviderRequiresReference(provider)) {
                failedCount++;
                item.put("status", "REFERENCE_FRAME_REQUIRED");
                item.put("message", "Choose a timeline frame for this shot before " + providerLabel + " can render it.");
                studioPayload.put("referenceFrameMissing", true);
                markVeoVariantReferenceRequired(variantId, studioPayload, "Reference frame required for " + providerLabel + " Studio Polish.");
                shotOutputs.add(item);
                int progress = 15 + (int) Math.round(((index + 1) * 70.0) / Math.max(1, acceptedTakes.size()));
                generationJobService.updateGenerationJobProgress(jobId, progress, "Shot " + take.shotNumber() + " needs a timeline frame before " + providerLabel + " polish.", Map.of("shots", shotOutputs));
                continue;
            }

            if (!properties.getAi().isShotVideoGenerationEnabled()) {
                item.put("status", "VIDEO_PROMPT_READY");
                item.put("message", providerLabel + " Studio Polish prompt prepared. Enable CREATOR_SHOT_VIDEO_GENERATION_ENABLED to render video.");
                item.put("providerTask", studioPayload.get("providerTask"));
                markVeoVariantPromptReady(variantId, studioPayload);
                shotOutputs.add(item);
                int progress = 15 + (int) Math.round(((index + 1) * 70.0) / Math.max(1, acceptedTakes.size()));
                generationJobService.updateGenerationJobProgress(jobId, progress, "Prepared " + providerLabel + " Studio Polish prompt for shot " + take.shotNumber(), Map.of("shots", shotOutputs));
                continue;
            }

            try {
                markVeoVariantPromptReady(variantId, studioPayload);
                try (StudioPolishProviderQueueService.ProviderQueueLease queueLease = acquireStudioPolishProviderSlot(
                        provider,
                        providerLabel,
                        jobId,
                        variantId,
                        take,
                        studioPayload
                )) {
                    studioPayload.put("providerQueue", queueLease.metadata());
                    item.put("providerQueue", queueLease.metadata());
                    log.info(
                            "Studio Polish all-shots provider slot acquired jobId={} takeId={} variantId={} shotNumber={} provider={} model={} seed={} queueMetadata={}",
                            jobId,
                            take.id(),
                            variantId,
                            take.shotNumber(),
                            provider,
                            stringValue(studioPayload.get("model"), ""),
                            longValue(studioPayload.get("seed")),
                            queueLease.metadata()
                    );
                    markVeoVariantRunning(variantId, studioPayload);
                    generationJobService.updateGenerationJobProgress(
                            jobId,
                            Math.min(95, 12 + (index * 80 / Math.max(1, acceptedTakes.size()))),
                            "Rendering " + providerLabel + " Studio Polish video for shot " + take.shotNumber(),
                            Map.of("shots", shotOutputs, "providerQueue", queueLease.metadata())
                    );
                    StudioPolishVideoGenerationService.GeneratedVideo generatedVideo = generateStudioPolishVideo(script, take, sourceImage, studioPayload);
                    log.info(
                            "Studio Polish all-shots provider output ready jobId={} takeId={} variantId={} shotNumber={} provider={} model={} operationId={} bytes={} contentType={}",
                            jobId,
                            take.id(),
                            variantId,
                            take.shotNumber(),
                            provider,
                            stringValue(generatedVideo.metadata().get("model"), stringValue(studioPayload.get("model"), "")),
                            generatedVideo.operationName(),
                            generatedVideo.bytes() == null ? 0 : generatedVideo.bytes().length,
                            generatedVideo.contentType()
                    );
                    publishProviderUsageFromMetadata(
                            "SHOT_TAKE_STUDIO_POLISH_ALL_" + provider.toUpperCase(Locale.ROOT),
                            generatedVideo.metadata(),
                            take,
                            jobId,
                            "Generated " + providerLabel + " Studio Polish shot video"
                    );
                    Map<String, Object> videoCostMetadata = objectMap(generatedVideo.metadata().get("costMetadata"));
                    if (!videoCostMetadata.isEmpty()) {
                        costMetadataItems.add(videoCostMetadata);
                        item.put("costMetadata", videoCostMetadata);
                    }
                    UUID finalVideoAssetId = storeVeoVideoAsset(script, take, variantId, sourceImage, generatedVideo);
                    markVeoVariantReady(variantId, finalVideoAssetId, generatedVideo);
                    generatedCount++;
                    item.put("status", "VIDEO_READY");
                    item.put("finalVideoAssetId", finalVideoAssetId.toString());
                    item.put("providerOperationId", generatedVideo.operationName());
                    item.put("message", providerLabel + " Studio Polish video is ready.");
                }
            } catch (RuntimeException ex) {
                failedCount++;
                item.put("status", "FAILED");
                item.put("message", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error(
                        "Studio Polish all-shots item failed jobId={} scriptId={} takeId={} variantId={} shotNumber={} provider={} model={} seed={} errorType={} errorMessage={}",
                        jobId,
                        script.getId(),
                        take.id(),
                        variantId,
                        take.shotNumber(),
                        provider,
                        stringValue(studioPayload.get("model"), ""),
                        longValue(studioPayload.get("seed")),
                        ex.getClass().getSimpleName(),
                        defaultString(ex.getMessage(), ex.getClass().getSimpleName()),
                        ex
                );
                markVeoVariantFailed(variantId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            }
            shotOutputs.add(item);

            int progress = 15 + (int) Math.round(((index + 1) * 70.0) / Math.max(1, acceptedTakes.size()));
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    progress,
                    "Processed " + providerLabel + " Studio Polish for shot " + take.shotNumber(),
                    Map.of("shots", shotOutputs)
            );
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("scriptId", script.getId().toString());
        output.put("status", failedCount == 0 ? "STUDIO_POLISH_VIDEO_READY" : generatedCount > 0 ? "STUDIO_POLISH_VIDEO_PARTIAL" : "STUDIO_POLISH_VIDEO_FAILED");
        output.put("generatedCount", generatedCount);
        output.put("failedCount", failedCount);
        output.put("message", failedCount == 0
                ? providerLabel + " Studio Polish videos are ready for accepted shots."
                : "Some accepted shots need a selected timeline frame or provider retry before all videos are ready.");
        output.put("shots", shotOutputs);
        Map<String, Object> aggregateCostMetadata = aggregateProviderCostMetadata("SHOT_TAKE_STUDIO_POLISH_ALL_" + provider.toUpperCase(Locale.ROOT), costMetadataItems);
        if (!aggregateCostMetadata.isEmpty()) {
            output.put("costMetadata", aggregateCostMetadata);
        }
        generationJobService.completeGenerationJob(jobId, output);
    }

    @Transactional
    public ShotTakeResponse completeStudioPolish(
            UUID takeId,
            UUID variantId,
            MultipartFile file,
            String metadataJson,
            String tenantId,
            String userId
    ) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        ensureVariantBelongsToTake(variantId, take.id());
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the final Studio Polish MP4.");
        }
        String contentType = defaultString(file.getContentType(), "video/mp4");
        if (!isVideoContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Final Studio Polish output must be a video file.");
        }
        String objectKey = "shot-enhancements/%s/%02d/studio-polish/final/%s-%s".formatted(
                take.scriptId(),
                take.shotNumber(),
                variantId,
                sanitizeFilename(file.getOriginalFilename())
        );
        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read Studio Polish output.", ex);
        }
        Map<String, Object> metadata = readMap(defaultString(metadataJson, "{}"));
        metadata.put("takeId", take.id().toString());
        metadata.put("scriptId", take.scriptId().toString());
        metadata.put("shotNumber", take.shotNumber());
        metadata.put("variantId", variantId.toString());
        metadata.put("assetKind", "studio_polish_final_video");
        metadata.put("provider", "studio_polish_cpu");
        CreatorAsset finalVideo = upsertAsset(CreatorAsset.builder()
                .tenantId(take.tenantId())
                .userId(take.userId())
                .projectId(take.projectId())
                .assetType(ASSET_TYPE_SHOT_STUDIO_POLISH_VIDEO)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());
        resetPolishedVideoAnalysis(take.id(), variantId, finalVideo.getId());
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'VIDEO_READY',
                    final_video_asset_id = ?,
                    provider = 'studio_polish_cpu',
                    provider_response = provider_response || cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                finalVideo.getId(),
                writeJson(Map.of(
                        "message", "Studio Polish final video uploaded.",
                        "status", "VIDEO_READY",
                        "finalVideoAssetId", finalVideo.getId().toString()
                )),
                variantId
        );
        return toTakeResponse(take.id());
    }

    @Transactional
    public ShotTakeResponse completeAudioEnhancement(
            UUID takeId,
            UUID variantId,
            MultipartFile file,
            String metadataJson,
            String tenantId,
            String userId
    ) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        ensureVariantBelongsToTake(variantId, take.id());
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the enhanced audio file.");
        }
        String contentType = defaultString(file.getContentType(), "audio/wav");
        if (!isAudioContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enhanced sound output must be an audio file.");
        }
        String objectKey = "shot-enhancements/%s/%02d/audio-enhance/%s-%s".formatted(
                take.scriptId(),
                take.shotNumber(),
                variantId,
                sanitizeFilename(file.getOriginalFilename())
        );
        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read enhanced audio output.", ex);
        }
        Map<String, Object> metadata = readMap(defaultString(metadataJson, "{}"));
        metadata.put("takeId", take.id().toString());
        metadata.put("scriptId", take.scriptId().toString());
        metadata.put("shotNumber", take.shotNumber());
        metadata.put("variantId", variantId.toString());
        metadata.put("assetKind", "shot_take_enhanced_audio");
        metadata.put("processingGoal", "production_audio_clarity_preserve_voice_texture");
        metadata.putIfAbsent("guardrail", "noise_reduction_and_clarity_only_no_voice_conversion_no_dialogue_rewrite");
        CreatorAsset finalAudio = upsertAsset(CreatorAsset.builder()
                .tenantId(take.tenantId())
                .userId(take.userId())
                .projectId(take.projectId())
                .assetType(ASSET_TYPE_SHOT_TAKE_ENHANCED_AUDIO)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());
        UUID sourceAssetId = uuidValue(metadata.get("sourceAssetId"));
        if (sourceAssetId != null && !sourceAssetId.equals(take.assetId())) {
            replaceSoundTimelineLayerAsset(take.id(), sourceAssetId, finalAudio, metadata);
        }
        Map<String, Object> providerResponsePatch = new LinkedHashMap<>();
        providerResponsePatch.put("message", "Enhanced production audio uploaded.");
        providerResponsePatch.put("status", "AUDIO_READY");
        providerResponsePatch.put("finalAudioAssetId", finalAudio.getId().toString());
        providerResponsePatch.put("finalAudioUrl", finalAudio.getPublicUrl());
        providerResponsePatch.put("guardrail", "Original voice texture, timing, words, and performance intent preserved.");
        providerResponsePatch.put("audioFixes", List.of(
                "background_noise_reduced",
                "speech_clarity_improved",
                "light_dereverb_if_needed",
                "loudness_normalized",
                "uploaded_snippet_mix_preserved"
        ));
        providerResponsePatch.put("workerMetadata", metadata);

        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'AUDIO_READY',
                    final_audio_asset_id = ?,
                    provider_response = provider_response || cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                finalAudio.getId(),
                writeJson(providerResponsePatch),
                variantId
        );
        Map<String, Object> autoFinalRender = autoRefreshFinalRenderAfterAudioReady(
                take,
                variantId,
                finalAudio,
                "audio_enhancement"
        );
        UUID jobId = generationJobIdForVariant(variantId);
        if (jobId != null) {
            Map<String, Object> previousOutput = generationJobOutput(jobId);
            Map<String, Object> costMetadata = objectMap(firstNonNull(
                    previousOutput.get("costMetadata"),
                    objectMap(previousOutput.get("audioEnhancement")).get("costMetadata")
            ));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("takeId", take.id().toString());
            output.put("variantId", variantId.toString());
            output.put("status", "AUDIO_READY");
            output.put("message", "Enhanced production audio is ready.");
            output.put("finalAudioAssetId", finalAudio.getId().toString());
            output.put("finalAudioUrl", finalAudio.getPublicUrl());
            if (!costMetadata.isEmpty()) {
                output.put("costMetadata", costMetadata);
            }
            if (previousOutput.get("providerTask") != null) {
                output.put("providerTask", previousOutput.get("providerTask"));
            }
            if (!autoFinalRender.isEmpty()) {
                output.put("autoFinalRender", autoFinalRender);
            }
            output.put("providerResponse", providerResponsePatch);
            output.put("take", toTakeResponse(take.id()));
            generationJobService.completeGenerationJob(jobId, output);
        }
        return toTakeResponse(take.id());
    }

    @Transactional
    public ShotTakeResponse completeAudioMix(
            UUID takeId,
            UUID variantId,
            MultipartFile file,
            String metadataJson,
            String tenantId,
            String userId
    ) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        ensureVariantBelongsToTake(variantId, take.id());
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the mixed audio file.");
        }
        String contentType = defaultString(file.getContentType(), "audio/wav");
        if (!isAudioContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mixed sound output must be an audio file.");
        }
        String objectKey = "shot-enhancements/%s/%02d/audio-mix/%s-%s".formatted(
                take.scriptId(),
                take.shotNumber(),
                variantId,
                sanitizeFilename(file.getOriginalFilename())
        );
        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read mixed audio output.", ex);
        }
        Map<String, Object> metadata = readMap(defaultString(metadataJson, "{}"));
        metadata.put("takeId", take.id().toString());
        metadata.put("scriptId", take.scriptId().toString());
        metadata.put("shotNumber", take.shotNumber());
        metadata.put("variantId", variantId.toString());
        metadata.put("assetKind", "shot_take_mixed_audio");
        metadata.put("processingGoal", "timeline_audio_mix_render");
        metadata.putIfAbsent("guardrail", "preserve_original_dialogue_sync_and_apply_timeline_layers_only");
        CreatorAsset finalAudio = upsertAsset(CreatorAsset.builder()
                .tenantId(take.tenantId())
                .userId(take.userId())
                .projectId(take.projectId())
                .assetType(ASSET_TYPE_SHOT_TAKE_MIXED_AUDIO)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());
        Map<String, Object> providerResponsePatch = new LinkedHashMap<>();
        providerResponsePatch.put("message", "Polished timeline audio mix uploaded.");
        providerResponsePatch.put("status", "AUDIO_MIX_READY");
        providerResponsePatch.put("finalAudioAssetId", finalAudio.getId().toString());
        providerResponsePatch.put("finalAudioUrl", finalAudio.getPublicUrl());
        providerResponsePatch.put("audioFixes", List.of(
                "timeline_layers_mixed",
                "volume_automation_applied",
                "dialogue_sync_preserved",
                "loudness_normalized"
        ));
        providerResponsePatch.put("workerMetadata", metadata);

        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'AUDIO_MIX_READY',
                    final_audio_asset_id = ?,
                    provider_response = provider_response || cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                finalAudio.getId(),
                writeJson(providerResponsePatch),
                variantId
        );
        Map<String, Object> autoFinalRender = autoRefreshFinalRenderAfterAudioReady(
                take,
                variantId,
                finalAudio,
                "audio_mix"
        );
        UUID jobId = generationJobIdForVariant(variantId);
        if (jobId != null) {
            Map<String, Object> previousOutput = generationJobOutput(jobId);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("takeId", take.id().toString());
            output.put("variantId", variantId.toString());
            output.put("status", "AUDIO_MIX_READY");
            output.put("message", "Polished timeline audio is ready.");
            output.put("finalAudioAssetId", finalAudio.getId().toString());
            output.put("finalAudioUrl", finalAudio.getPublicUrl());
            if (previousOutput.get("providerTask") != null) {
                output.put("providerTask", previousOutput.get("providerTask"));
            }
            if (!autoFinalRender.isEmpty()) {
                output.put("autoFinalRender", autoFinalRender);
            }
            output.put("audioMix", previousOutput.get("audioMix"));
            output.put("providerResponse", providerResponsePatch);
            output.put("take", toTakeResponse(take.id()));
            generationJobService.completeGenerationJob(jobId, output);
        }
        return toTakeResponse(take.id());
    }

    private Map<String, Object> autoRefreshFinalRenderAfterAudioReady(
            ShotTakeData take,
            UUID audioVariantId,
            CreatorAsset finalAudio,
            String trigger
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        VariantMediaData activeVideo = loadLatestFinalVideoMediaData(take.id());
        if (activeVideo == null || activeVideo.finalVideoAssetId() == null) {
            result.put("status", "SKIPPED");
            result.put("reason", "no_polished_video_ready");
            log.info(
                    "Auto final render skipped after audio ready takeId={} audioVariantId={} reason=no_polished_video_ready",
                    take.id(),
                    audioVariantId
            );
            return result;
        }

        boolean hadFinalRender = activeVideo.finalRenderAssetId() != null;
        Map<String, Object> linkPatch = new LinkedHashMap<>();
        linkPatch.put("audioIntegratedAt", OffsetDateTime.now().toString());
        linkPatch.put("audioIntegrationTrigger", trigger);
        linkPatch.put("sourceAudioVariantId", audioVariantId.toString());
        linkPatch.put("sourceFinalAudioAssetId", finalAudio.getId().toString());
        linkPatch.put("sourceFinalAudioUrl", finalAudio.getPublicUrl());
        linkPatch.put("autoFinalRender", Map.of(
                "status", hadFinalRender ? "REFRESHING" : "LINKED_FOR_PREVIEW",
                "reason", hadFinalRender
                        ? "final_render_existed_before_audio_polish"
                        : "no_existing_final_render_to_refresh"
        ));
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set final_audio_asset_id = ?,
                    final_render_asset_id = case when ? then null else final_render_asset_id end,
                    status = case when ? then 'VIDEO_READY' else status end,
                    provider_response = provider_response || cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                finalAudio.getId(),
                hadFinalRender,
                hadFinalRender,
                writeJson(linkPatch),
                activeVideo.variantId()
        );

        result.put("status", hadFinalRender ? "REFRESHING" : "LINKED_FOR_PREVIEW");
        result.put("targetVariantId", activeVideo.variantId().toString());
        result.put("sourceAudioVariantId", audioVariantId.toString());
        result.put("sourceFinalAudioAssetId", finalAudio.getId().toString());
        result.put("clearedStaleFinalRender", hadFinalRender);

        if (!hadFinalRender) {
            log.info(
                    "Audio linked to active polished video takeId={} targetVariantId={} audioVariantId={} finalAudioAssetId={} autoRender=false",
                    take.id(),
                    activeVideo.variantId(),
                    audioVariantId,
                    finalAudio.getId()
            );
            return result;
        }

        CreatorGenerationJob renderJob = null;
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("takeId", take.id().toString());
            input.put("scriptId", take.scriptId().toString());
            input.put("shotNumber", take.shotNumber());
            input.put("variantId", activeVideo.variantId().toString());
            input.put("mode", "SHOT_TAKE_FINAL_RENDER");
            input.put("provider", "local_ffmpeg");
            input.put("model", "ffmpeg-final-video-render-v1");
            input.put("trigger", "audio_ready_auto_refresh");
            input.put("sourceAudioVariantId", audioVariantId.toString());
            input.put("sourceFinalAudioAssetId", finalAudio.getId().toString());
            input.put("sourceFinalVideoAssetId", activeVideo.finalVideoAssetId().toString());
            input.put("burnTextOverlays", true);
            input.put("useMixedAudio", true);
            renderJob = generationJobService.startGenerationJob(
                    "SHOT_TAKE_FINAL_RENDER",
                    take.tenantId(),
                    take.userId(),
                    take.projectId(),
                    input
            );
            ShotTakeFinalRenderRequest renderRequest = new ShotTakeFinalRenderRequest(
                    activeVideo.variantId(),
                    true,
                    true,
                    Map.of(
                            "trigger", "audio_ready_auto_refresh",
                            "sourceAudioVariantId", audioVariantId.toString(),
                            "sourceFinalAudioAssetId", finalAudio.getId().toString()
                    )
            );
            runFinalRenderJob(renderJob.getId(), take.id(), renderRequest, take.tenantId(), take.userId());
            result.put("status", "REFRESHED");
            result.put("jobId", renderJob.getId().toString());
            log.info(
                    "Auto final render refreshed after audio ready jobId={} takeId={} targetVariantId={} audioVariantId={} finalAudioAssetId={}",
                    renderJob.getId(),
                    take.id(),
                    activeVideo.variantId(),
                    audioVariantId,
                    finalAudio.getId()
            );
        } catch (RuntimeException ex) {
            result.put("status", "FAILED");
            result.put("message", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            if (renderJob != null) {
                generationJobService.failGenerationJob(renderJob.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            }
            jdbcTemplate.update(
                    """
                    update creator_shot_enhancement_variants
                    set provider_response = provider_response || cast(? as jsonb),
                        updated_at = now()
                    where id = ?
                    """,
                    writeJson(Map.of(
                            "autoFinalRender", Map.of(
                                    "status", "FAILED",
                                    "message", defaultString(ex.getMessage(), ex.getClass().getSimpleName()),
                                    "fallback", "polished_video_plus_external_audio"
                            )
                    )),
                    activeVideo.variantId()
            );
            log.error(
                    "Auto final render refresh failed after audio ready takeId={} targetVariantId={} audioVariantId={} errorType={} errorMessage={}",
                    take.id(),
                    activeVideo.variantId(),
                    audioVariantId,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex
            );
        }
        return result;
    }

    @Transactional
    public ShotTakeResponse generatePolishedFrameTimeline(UUID takeId, ShotTakePolishedFramesRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        VariantMediaData media = loadVariantMediaData(take.id(), request == null ? null : request.variantId(), true);
        int sampleCount = request == null || request.sampleCount() == null ? 10 : request.sampleCount();
        boolean useFinalRenderFrames = media.finalRenderAssetId() != null
                && !stringValue(media.finalRenderBucket(), "").isBlank()
                && !stringValue(media.finalRenderObjectKey(), "").isBlank();
        UUID frameSourceAssetId = useFinalRenderFrames ? media.finalRenderAssetId() : media.finalVideoAssetId();
        String frameSourceBucket = useFinalRenderFrames ? media.finalRenderBucket() : media.finalVideoBucket();
        String frameSourceObjectKey = useFinalRenderFrames ? media.finalRenderObjectKey() : media.finalVideoObjectKey();
        String frameSourceContentType = useFinalRenderFrames ? media.finalRenderContentType() : media.finalVideoContentType();
        LocalVideoFrameExtractionService.FrameTimeline timeline = localVideoFrameExtractionService.extract(
                take.scriptId(),
                take.shotNumber(),
                take.id(),
                media.variantId(),
                frameSourceAssetId,
                frameSourceBucket,
                frameSourceObjectKey,
                frameSourceContentType,
                sampleCount
        );

        boolean persist = request == null || request.persist() == null || Boolean.TRUE.equals(request.persist());
        if (persist) {
            Map<String, Object> analysis = loadTakeMediaAnalysis(take.id());
            Map<String, Object> polishedVideo = new LinkedHashMap<>(timeline.metadata());
            polishedVideo.put("frames", timeline.frames());
            polishedVideo.put("sourceVariantId", media.variantId().toString());
            polishedVideo.put("sourceVideoAssetId", frameSourceAssetId.toString());
            polishedVideo.put("sourceVideoRole", useFinalRenderFrames ? "final_render_with_baked_text" : "polished_video");
            polishedVideo.put("updatedAt", OffsetDateTime.now().toString());
            analysis.put("polishedVideo", polishedVideo);
            analysis.put("updatedAt", OffsetDateTime.now().toString());
            saveTakeMediaAnalysis(take.id(), analysis);
        }

        Map<String, Object> providerResponsePatch = new LinkedHashMap<>();
        providerResponsePatch.put("polishedFrameTimeline", Map.of(
                "source", "backend_ffmpeg",
                "frameCount", timeline.frames().size(),
                "sourceVideoAssetId", frameSourceAssetId.toString(),
                "sourceVideoRole", useFinalRenderFrames ? "final_render_with_baked_text" : "polished_video",
                "updatedAt", OffsetDateTime.now().toString()
        ));
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set provider_response = provider_response || cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                writeJson(providerResponsePatch),
                media.variantId()
        );
        return toTakeResponse(take.id());
    }

    @Transactional
    public CreatorGenerationJob startFinalRenderJob(UUID takeId, ShotTakeFinalRenderRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        VariantMediaData media = loadVariantMediaData(take.id(), request == null ? null : request.variantId(), true);
        Map<String, Object> analysis = loadTakeMediaAnalysis(take.id());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("takeId", take.id().toString());
        input.put("scriptId", take.scriptId().toString());
        input.put("shotNumber", take.shotNumber());
        input.put("variantId", media.variantId().toString());
        input.put("mode", "SHOT_TAKE_FINAL_RENDER");
        input.put("provider", "local_ffmpeg");
        input.put("model", "ffmpeg-final-video-render-v1");
        input.put("sourceFinalVideoAssetId", media.finalVideoAssetId().toString());
        input.put("sourceFinalAudioAssetId", media.finalAudioAssetId() == null ? null : media.finalAudioAssetId().toString());
        input.put("burnTextOverlays", request == null || request.burnTextOverlays() == null || Boolean.TRUE.equals(request.burnTextOverlays()));
        input.put("useMixedAudio", request == null || request.useMixedAudio() == null || Boolean.TRUE.equals(request.useMixedAudio()));
        input.put("textOverlayCount", textOverlayList(analysis.get("textOverlays")).size());
        if (request != null && request.metadata() != null && !request.metadata().isEmpty()) {
            input.put("metadata", toStringObjectMap(request.metadata()));
        }
        return generationJobService.startGenerationJob(
                "SHOT_TAKE_FINAL_RENDER",
                take.tenantId(),
                take.userId(),
                take.projectId(),
                input
        );
    }

    @Transactional
    public ShotTakeResponse runFinalRenderJob(UUID jobId, UUID takeId, ShotTakeFinalRenderRequest request, String tenantId, String userId) {
        ShotTakeData take = loadTake(takeId, tenantId, userId);
        Map<String, Object> jobInput = takeJobInput(jobId);
        UUID variantId = request == null ? null : request.variantId();
        if (variantId == null) {
            variantId = uuidValue(jobInput.get("variantId"));
        }
        VariantMediaData media = loadVariantMediaData(take.id(), variantId, true);
        Map<String, Object> analysis = loadTakeMediaAnalysis(take.id());
        boolean burnText = request == null || request.burnTextOverlays() == null || Boolean.TRUE.equals(request.burnTextOverlays());
        boolean useMixedAudio = request == null || request.useMixedAudio() == null || Boolean.TRUE.equals(request.useMixedAudio());
        List<Map<String, Object>> overlays = burnText ? textOverlayList(analysis.get("textOverlays")) : List.of();

        Map<String, Object> renderOptions = new LinkedHashMap<>();
        renderOptions.put("burnTextOverlays", burnText);
        renderOptions.put("useMixedAudio", useMixedAudio);
        renderOptions.put("textOverlayCount", overlays.size());
        renderOptions.put("sourceFinalVideoAssetId", media.finalVideoAssetId().toString());
        VariantMediaData audioMedia = useMixedAudio && media.finalAudioAssetId() == null
                ? loadLatestFinalAudioMediaData(take.id())
                : media;
        boolean hasFinalAudio = useMixedAudio && audioMedia.finalAudioAssetId() != null;
        boolean useOriginalTakeAudio = useMixedAudio
                && !hasFinalAudio
                && (isVideoContentType(take.contentType()) || isAudioContentType(take.contentType()))
                && !stringValue(take.bucket(), "").isBlank()
                && !stringValue(take.objectKey(), "").isBlank();
        renderOptions.put("sourceFinalAudioAssetId", hasFinalAudio ? audioMedia.finalAudioAssetId().toString() : null);
        renderOptions.put("sourceFinalAudioVariantId", hasFinalAudio ? audioMedia.variantId().toString() : null);
        renderOptions.put("sourceOriginalAudioAssetId", useOriginalTakeAudio ? take.assetId().toString() : null);
        renderOptions.put("audioSource", hasFinalAudio ? "enhanced_or_mixed_audio" : useOriginalTakeAudio ? "original_take_audio" : "polished_video_embedded_audio");

        generationJobService.updateGenerationJobProgress(
                jobId,
                45,
                "Rendering final MP4 with polished video, audio, and text overlays.",
                Map.of("takeId", take.id().toString(), "variantId", media.variantId().toString(), "renderOptions", renderOptions)
        );

        LocalFinalVideoRenderService.RenderedVideo renderedVideo = localFinalVideoRenderService.render(
                take.scriptId(),
                take.shotNumber(),
                take.id(),
                media.variantId(),
                media.finalVideoBucket(),
                media.finalVideoObjectKey(),
                media.finalVideoContentType(),
                hasFinalAudio ? audioMedia.finalAudioBucket() : useOriginalTakeAudio ? take.bucket() : null,
                hasFinalAudio ? audioMedia.finalAudioObjectKey() : useOriginalTakeAudio ? take.objectKey() : null,
                hasFinalAudio ? audioMedia.finalAudioContentType() : useOriginalTakeAudio ? take.contentType() : null,
                overlays,
                renderOptions
        );

        String objectKey = "shot-enhancements/%s/%02d/final-render/%s.mp4".formatted(take.scriptId(), take.shotNumber(), media.variantId());
        AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAsset(
                objectKey,
                renderedVideo.bytes(),
                renderedVideo.contentType(),
                SIGNED_URL_TTL
        );
        Map<String, Object> metadata = new LinkedHashMap<>(renderedVideo.metadata());
        metadata.put("takeId", take.id().toString());
        metadata.put("scriptId", take.scriptId().toString());
        metadata.put("shotNumber", take.shotNumber());
        metadata.put("variantId", media.variantId().toString());
        metadata.put("assetKind", "shot_take_final_render_video");
        metadata.put("sourceFinalVideoAssetId", media.finalVideoAssetId().toString());
        if (useMixedAudio && audioMedia.finalAudioAssetId() != null) {
            metadata.put("sourceFinalAudioAssetId", audioMedia.finalAudioAssetId().toString());
            metadata.put("sourceFinalAudioVariantId", audioMedia.variantId().toString());
        }
        if (useOriginalTakeAudio) {
            metadata.put("sourceOriginalAudioAssetId", take.assetId().toString());
            metadata.put("sourceOriginalAudioRole", "fallback_until_polished_audio_exists");
        }

        CreatorAsset finalRender = upsertAsset(CreatorAsset.builder()
                .tenantId(take.tenantId())
                .userId(take.userId())
                .projectId(take.projectId())
                .assetType(ASSET_TYPE_SHOT_TAKE_FINAL_RENDER_VIDEO)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> providerResponsePatch = new LinkedHashMap<>();
        providerResponsePatch.put("message", "Final MP4 rendered with polished video, audio, and text overlays.");
        providerResponsePatch.put("status", "FINAL_RENDER_READY");
        providerResponsePatch.put("finalRenderAssetId", finalRender.getId().toString());
        providerResponsePatch.put("finalRenderUrl", finalRender.getPublicUrl());
        providerResponsePatch.put("renderOptions", renderOptions);
        providerResponsePatch.put("workerMetadata", metadata);
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'FINAL_RENDER_READY',
                    final_render_asset_id = ?,
                    provider_response = provider_response || cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                finalRender.getId(),
                writeJson(providerResponsePatch),
                media.variantId()
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("takeId", take.id().toString());
        output.put("variantId", media.variantId().toString());
        output.put("status", "FINAL_RENDER_READY");
        output.put("message", "Final MP4 is ready for preview/export.");
        output.put("finalRenderAssetId", finalRender.getId().toString());
        output.put("finalRenderUrl", finalRender.getPublicUrl());
        output.put("renderOptions", renderOptions);
        output.put("take", toTakeResponse(take.id()));
        generationJobService.completeGenerationJob(jobId, output);
        return toTakeResponse(take.id());
    }

    @Transactional
    public CreatorGenerationJob startEnhanceAllJob(UUID scriptId, EnhanceAllShotTakesRequest request, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<ShotTakeData> acceptedTakes = loadAcceptedTakes(script.getId(), tenantId, userId);
        if (acceptedTakes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accept at least one shot take before applying polish to all shots.");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scriptId", script.getId().toString());
        input.put("approvedVariantId", request == null || request.approvedVariantId() == null ? null : request.approvedVariantId().toString());
        input.put("editNote", defaultString(request == null ? null : request.editNote(), ""));
        input.put("acceptedTakeCount", acceptedTakes.size());
        input.put("shotNumbers", acceptedTakes.stream().map(ShotTakeData::shotNumber).toList());
        input.put("provider", "google_veo");
        input.put("model", properties.getAi().getGeminiVideoModel());
        input.put("resolution", properties.getAi().getGeminiVideoResolution());
        input.put("durationSeconds", properties.getAi().getGeminiVideoDurationSeconds());
        input.put("nativeAudioRequested", true);
        return generationJobService.startGenerationJob(
                "SHOT_TAKE_ENHANCE_ALL",
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                input
        );
    }

    public void runEnhanceAllJob(UUID jobId, UUID scriptId, EnhanceAllShotTakesRequest request, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<ShotTakeData> acceptedTakes = loadAcceptedTakes(script.getId(), tenantId, userId);
        List<Map<String, Object>> shotOutputs = new ArrayList<>();
        List<Map<String, Object>> costMetadataItems = new ArrayList<>();
        int generatedCount = 0;
        int failedCount = 0;
        for (int index = 0; index < acceptedTakes.size(); index++) {
            ShotTakeData take = acceptedTakes.get(index);
            CreatorScriptShotPlan plan = loadShotPlan(script.getId(), take.shotNumber());
            ReferenceImage sourceImage = resolveVeoSourceImage(take, request == null ? null : request.approvedVariantId());
            Map<String, Object> promptPayload = buildVeoPromptPayload(script, plan, take, request, sourceImage);
            UUID variantId = createVeoVariant(take.id(), jobId, promptPayload);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("takeId", take.id().toString());
            item.put("shotNumber", take.shotNumber());
            item.put("variantId", variantId.toString());
            item.put("provider", "google_veo");
            item.put("referenceAssetId", sourceImage == null ? null : sourceImage.assetId().toString());
            if (sourceImage == null) {
                failedCount++;
                item.put("status", "REFERENCE_FRAME_REQUIRED");
                item.put("message", "Upload a still frame or generate a polished image preview before Veo can render this shot video.");
                markVeoVariantFailed(variantId, "Reference image/frame required for Google Veo.");
                shotOutputs.add(item);
                int progress = 15 + (int) Math.round(((index + 1) * 70.0) / Math.max(1, acceptedTakes.size()));
                generationJobService.updateGenerationJobProgress(jobId, progress, "Shot " + take.shotNumber() + " needs a frame before Veo video.", Map.of("shots", shotOutputs));
                continue;
            }

            if (!properties.getAi().isShotVideoGenerationEnabled()) {
                item.put("status", "VEO_PROMPT_READY");
                item.put("message", "Google Veo prompt prepared. Enable CREATOR_SHOT_VIDEO_GENERATION_ENABLED to render video.");
                markVeoVariantPromptReady(variantId, promptPayload);
                shotOutputs.add(item);
                int progress = 15 + (int) Math.round(((index + 1) * 70.0) / Math.max(1, acceptedTakes.size()));
                generationJobService.updateGenerationJobProgress(jobId, progress, "Prepared Veo prompt for shot " + take.shotNumber(), Map.of("shots", shotOutputs));
                continue;
            }

            try {
                markVeoVariantRunning(variantId, promptPayload);
                generationJobService.updateGenerationJobProgress(jobId, Math.min(95, 12 + (index * 80 / Math.max(1, acceptedTakes.size()))), "Rendering Veo video for shot " + take.shotNumber(), Map.of("shots", shotOutputs));
                StudioPolishVideoGenerationService.GeneratedVideo generatedVideo = generateStudioPolishVideo(script, take, sourceImage, promptPayload);
                publishProviderUsageFromMetadata(
                        "SHOT_TAKE_ENHANCE_ALL_VEO",
                        generatedVideo.metadata(),
                        take,
                        jobId,
                        "Generated premium Veo shot polish"
                );
                Map<String, Object> videoCostMetadata = objectMap(generatedVideo.metadata().get("costMetadata"));
                if (!videoCostMetadata.isEmpty()) {
                    costMetadataItems.add(videoCostMetadata);
                    item.put("costMetadata", videoCostMetadata);
                }
                UUID finalVideoAssetId = storeVeoVideoAsset(script, take, variantId, sourceImage, generatedVideo);
                markVeoVariantReady(variantId, finalVideoAssetId, generatedVideo);
                generatedCount++;
                item.put("status", "VIDEO_READY");
                item.put("finalVideoAssetId", finalVideoAssetId.toString());
                item.put("providerOperationId", generatedVideo.operationName());
                item.put("message", "Google Veo polished video with native sound/foley is ready.");
            } catch (RuntimeException ex) {
                failedCount++;
                item.put("status", "FAILED");
                item.put("message", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                markVeoVariantFailed(variantId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            }
            shotOutputs.add(item);
            int progress = 15 + (int) Math.round(((index + 1) * 70.0) / Math.max(1, acceptedTakes.size()));
            generationJobService.updateGenerationJobProgress(jobId, progress, "Processed Veo polish for shot " + take.shotNumber(), Map.of("shots", shotOutputs));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("scriptId", script.getId().toString());
        output.put("status", failedCount == 0 ? "VEO_VIDEOS_READY" : generatedCount > 0 ? "VEO_PARTIAL" : "VEO_FAILED");
        output.put("generatedCount", generatedCount);
        output.put("failedCount", failedCount);
        output.put("message", failedCount == 0
                ? "Google Veo polished videos and native sound/foley are ready for accepted shots."
                : "Some shots need a frame or provider retry before all Veo videos are ready.");
        output.put("shots", shotOutputs);
        Map<String, Object> aggregateCostMetadata = aggregateProviderCostMetadata("SHOT_TAKE_ENHANCE_ALL_VEO", costMetadataItems);
        if (!aggregateCostMetadata.isEmpty()) {
            output.put("costMetadata", aggregateCostMetadata);
        }
        generationJobService.completeGenerationJob(jobId, output);
    }

    private Map<String, Object> buildDeterministicChecks(ShotTakeData take, CreatorScriptShotPlan plan) {
        boolean validMediaType = isImageContentType(take.contentType()) || isVideoContentType(take.contentType());
        boolean sizeOk = take.sizeBytes() != null && take.sizeBytes() > 0 && take.sizeBytes() <= 750L * 1024L * 1024L;
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("assetUploaded", true);
        checks.put("contentType", take.contentType());
        checks.put("contentTypeValid", validMediaType);
        checks.put("sizeBytes", take.sizeBytes());
        checks.put("sizeValid", sizeOk);
        checks.put("productionPlanAvailable", plan != null);
        checks.put("cameraAngleSemanticCheck", "USER_CONFIRMATION_REQUIRED");
        checks.put("actorDialogueSemanticCheck", "USER_CONFIRMATION_REQUIRED");
        checks.put("lightingSemanticCheck", "USER_CONFIRMATION_REQUIRED");
        checks.put("soundTimelineSupportsOverlap", true);
        checks.put("hardFail", !validMediaType || !sizeOk);
        return checks;
    }

    private Map<String, Object> buildPreviousShotContinuityContext(UUID scriptId, int shotNumber) {
        return buildPreviousShotContinuityContext(scriptId, shotNumber, null);
    }

    private Map<String, Object> buildPreviousShotContinuityContext(UUID scriptId, int shotNumber, ContinuityReferenceImage continuityReference) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("purpose", "Use prior shot JSON as continuity reference only. Current shot plan, reference frame, and user note always win.");
        context.put("currentShotNumber", shotNumber);
        context.put("visualReference", continuityReferenceSummary(continuityReference));
        context.put("previousShotPlans", previousShotPlans(scriptId, shotNumber));
        context.put("previousEnhancementRecipes", previousEnhancementRecipes(scriptId, shotNumber));
        context.put("continuityRules", List.of(
                "keep_color_grade_family_consistent",
                "keep_lighting_language_consistent",
                "keep_background_and_set_design_family_consistent",
                "keep_actor_look_wardrobe_skin_tone_natural_and_consistent",
                "carry_forward_last_user_wardrobe_choice_when_current_note_is_empty",
                "do_not_copy_previous_composition_when_current_shot_plan_differs",
                "current_shot_reference_image_is_source_of_truth"
        ));
        return context;
    }

    private Map<String, Object> continuityReferenceSummary(ContinuityReferenceImage continuityReference) {
        if (continuityReference == null) {
            return Map.of("available", false);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", true);
        summary.put("variantId", continuityReference.variantId().toString());
        summary.put("takeId", continuityReference.takeId().toString());
        summary.put("shotNumber", continuityReference.shotNumber());
        summary.put("status", continuityReference.status());
        summary.put("provider", continuityReference.provider());
        summary.put("imageRole", continuityReference.referenceImage().role());
        summary.put("recipe", compactContinuityRecipe(continuityReference.promptPayload()));
        return summary;
    }

    private List<Map<String, Object>> previousShotPlans(UUID scriptId, int shotNumber) {
        return jdbcTemplate.query(
                """
                select shot_number,
                       storyboard_tag::text as storyboard_tag_json,
                       lighting_build_sheet_tag::text as lighting_tag_json,
                       camera_plan_sheet_tag::text as camera_tag_json,
                       updated_at
                from creator_script_shot_plans
                where script_id = ?
                  and shot_number < ?
                order by shot_number desc
                limit 3
                """,
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("shotNumber", rs.getInt("shot_number"));
                    item.put("storyboardTag", readMap(rs.getString("storyboard_tag_json")));
                    item.put("lightingBuildSheetTag", readMap(rs.getString("lighting_tag_json")));
                    item.put("cameraPlanSheetTag", readMap(rs.getString("camera_tag_json")));
                    item.put("updatedAt", stringValue(rs.getObject("updated_at"), ""));
                    return item;
                },
                scriptId,
                shotNumber
        );
    }

    private List<Map<String, Object>> previousEnhancementRecipes(UUID scriptId, int shotNumber) {
        return jdbcTemplate.query(
                """
                select t.shot_number,
                       v.status,
                       v.provider,
                       v.prompt_payload::text as prompt_payload_json,
                       v.updated_at
                from creator_shot_enhancement_variants v
                join creator_shot_takes t on t.id = v.take_id
                where t.script_id = ?
                  and t.shot_number < ?
                  and v.prompt_payload is not null
                  and v.prompt_payload <> '{}'::jsonb
                order by t.shot_number desc, v.updated_at desc
                limit 3
                """,
                (rs, rowNum) -> {
                    Map<String, Object> promptPayload = readMap(rs.getString("prompt_payload_json"));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("shotNumber", rs.getInt("shot_number"));
                    item.put("status", rs.getString("status"));
                    item.put("provider", rs.getString("provider"));
                    item.put("updatedAt", stringValue(rs.getObject("updated_at"), ""));
                    item.put("recipe", compactContinuityRecipe(promptPayload));
                    return item;
                },
                scriptId,
                shotNumber
        );
    }

    private Map<String, Object> compactContinuityRecipe(Map<String, Object> promptPayload) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        copyIfPresent(promptPayload, recipe, "mode");
        copyIfPresent(promptPayload, recipe, "preset");
        copyIfPresent(promptPayload, recipe, "plateMode");
        copyIfPresent(promptPayload, recipe, "editNote");
        copyIfPresent(promptPayload, recipe, "storyboardTag");
        copyIfPresent(promptPayload, recipe, "lightingBuildSheetTag");
        copyIfPresent(promptPayload, recipe, "cameraPlanSheetTag");
        copyIfPresent(promptPayload, recipe, "lookRecipe");
        copyIfPresent(promptPayload, recipe, "opencvPlan");
        copyIfPresent(promptPayload, recipe, "opencvTransformationRecipe");
        copyIfPresent(promptPayload, recipe, "copyableTransformationRecipe");
        copyIfPresent(promptPayload, recipe, "studioLookPreset");
        copyIfPresent(promptPayload, recipe, "studioLookRecipe");
        copyIfPresent(promptPayload, recipe, "productionDesignContext");
        copyIfPresent(promptPayload, recipe, "continuityContextForNextShot");
        Object prompt = promptPayload.get("prompt");
        if (prompt != null && !String.valueOf(prompt).isBlank()) {
            recipe.put("promptPreview", shortText(String.valueOf(prompt), 900));
        }
        return recipe;
    }

    private String shortText(String value, int maxLength) {
        String text = defaultString(value, "").replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength)) + "...";
    }

    private String logSnippet(Object value, int maxLength) {
        return shortText(value == null ? "" : String.valueOf(value), maxLength);
    }

    private boolean isGeminiNoImageFailure(Exception ex) {
        String message = ex == null ? "" : defaultString(ex.getMessage(), "").toUpperCase(Locale.ROOT);
        return message.contains("NO INLINE IMAGE")
                || message.contains("IMAGE_OTHER")
                || message.contains("IMAGE_SAFETY")
                || message.contains("IMAGE_PROHIBITED_CONTENT")
                || message.contains("NO_IMAGE");
    }

    private String buildCompactImageEditPrompt(
            String editNote,
            Map<String, Object> storyboardTag,
            Map<String, Object> lightingTag,
            Map<String, Object> cameraTag,
            Map<String, Object> continuityContext,
            boolean safeRetry
    ) {
        String userNote = shortText(defaultString(editNote, ""), safeRetry ? 180 : 420);
        if (userNote.isBlank()) {
            userNote = "No extra user edit. Use production design and continuity only.";
        }
        if (safeRetry) {
            return """
                    Edit the provided image as a subtle cinematic creator-video cleanup.
                    Preserve the exact same actor face and identity. Do not change facial geometry, age, ethnicity, skin tone identity, eye shape/color, nose, lips, jawline, face width, teeth, moles/scars, or distinguishing facial features.
                    Clothes, environment, lighting, grade, hair styling, and natural surface makeup may change if requested, but the actual face must remain intact.
                    Preserve expression, mouth shape, pose, hands, camera framing, and foreground geometry.
                    If a second reference image is provided, use it only for continuity of wardrobe, actor look, color grade, and lighting language.
                    The current frame remains the source of truth for pose, lips, hands, and composition.
                    Do not add text, logos, extra people, extra limbs, new objects, or identity changes.
                    Improve only lighting quality, color grade, exposure, background cleanup, shadows, and overall polish.
                    Additive user note, only if safe and consistent with the reference image: %s
                    Lighting summary: %s
                    Camera/framing summary: %s
                    """.formatted(
                    userNote,
                    shortText(writeJson(lightingTag), 700),
                    shortText(writeJson(cameraTag), 500)
            ).trim();
        }
        return """
                Edit the provided image for a polished creator-video shot.
                The uploaded image is the source of truth. Preserve the exact actor face and identity: facial geometry, age, ethnicity, skin tone identity, eye shape/color, nose, lips, jawline, face width, teeth, moles/scars, and distinguishing facial features must remain intact.
                Clothes, environment, lighting, grade, hair styling, and natural surface makeup may change if requested, but do not alter the actual face.
                Preserve expression, mouth/lip shape, pose, hands, camera angle, framing, foreground geometry, and realistic body proportions.
                If a second reference image is present, it is the previous polished variant. Carry forward its wardrobe choice, actor look, facial continuity, color grade, lighting language, and set-design family.
                Do not copy the second reference image's pose, framing, background layout, or mouth shape over the current frame.
                Apply production design, cinematic lighting, color grade, background cleanup, set polish, and image-quality enhancement.
                User edit note is additive on top of production design, not a replacement: %s
                Do not add text overlays, logos, extra people, extra limbs, watermarks, or unrelated foreground objects.
                Storyboard summary: %s
                Lighting summary: %s
                Camera summary: %s
                Prior continuity summary: %s
                """.formatted(
                userNote,
                shortText(writeJson(storyboardTag), 900),
                shortText(writeJson(lightingTag), 800),
                shortText(writeJson(cameraTag), 650),
                shortText(writeJson(continuityContext), 700)
        ).trim();
    }

    private Map<String, Object> buildEnhancementPromptPayload(
            CreatorScript script,
            CreatorScriptShotPlan plan,
            ShotTakeData take,
            ShotTakeEnhancePreviewRequest request,
            ContinuityReferenceImage continuityReference
    ) {
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        Map<String, Object> lightingTag = plan == null ? Map.of() : safeMap(plan.getLightingBuildSheetTag());
        Map<String, Object> cameraTag = plan == null ? Map.of() : safeMap(plan.getCameraPlanSheetTag());
        List<Map<String, Object>> soundTimeline = buildSoundTimeline(script, plan, take.shotNumber());
        Map<String, Object> continuityContext = buildPreviousShotContinuityContext(script.getId(), take.shotNumber(), continuityReference);
        ReferenceImage referenceImage = resolveReferenceImage(take);
        String editNote = defaultString(request == null ? null : request.editNote(), "");
        String imagePrompt = buildCompactImageEditPrompt(
                editNote,
                storyboardTag,
                lightingTag,
                cameraTag,
                continuityContext,
                false
        );
        String safeRetryImagePrompt = buildCompactImageEditPrompt(
                editNote,
                storyboardTag,
                lightingTag,
                cameraTag,
                continuityContext,
                true
        );
        String prompt = """
                Edit the provided image. This is image-to-image polishing for a user-recorded creator shot.

                Scene lock rules:
                - Face identity is locked. Preserve the exact actor face and identity from the current reference frame.
                - Do not change facial geometry, age, ethnicity, skin tone identity, eye shape/color, nose, lips, jawline, face width, teeth, moles/scars, or distinguishing facial features.
                - Clothes, environment, lighting, grade, hair styling, and natural surface makeup may change if requested, but the actual face must remain intact.
                - Preserve expression, mouth shape, body pose, hands, props, shot framing, camera angle, and foreground geometry.
                - Do not invent a new person, change the actor age/gender, alter dialogue mouth shape, or move the subject.
                - Do not add text overlays, logos, extra people, extra limbs, or new foreground objects.
                - Only improve lighting, shadows, color grade, background polish, production design, set dressing, cleanup, and perceived image quality.

                Apply the lighting/build sheet and production design while keeping the input image as the source of truth.
                Follow the storyboard, lighting, DP/camera, and overlapping sound/foley timeline JSON below.
                Enhancement policy:
                - Always start from production design JSON, lightingBuildSheetTag, cameraPlanSheetTag, soundTimeline, and previousShotContinuityContext.
                - User edit note is additive, not a replacement. Apply it on top of the production design.
                - Example: if the user asks "actor wears tank top", keep the same production design, camera, lighting, pose, face, expression, and lip-sync; only adapt wardrobe toward a tank top.
                - If user edit note is empty, do a continuity enhancement using production design plus previousShotContinuityContext.
                - Previous user visual choices in previousShotContinuityContext, such as wardrobe/look changes, should carry forward unless the current user edit note or hard current shot reference conflicts.
                - Current shot reference image is the source of truth for face, expression, mouth/lip-sync, pose, framing, and hands.
                - If a second image is attached, it is the previous polished variant continuity reference. Carry forward wardrobe, actor look, facial continuity, color grade, lighting language, and set-design family from it.
                - Never copy the second image's pose, framing, mouth shape, or background layout when it conflicts with the current shot reference frame.
                User edit note to append: %s

                storyboardTag: %s
                lightingBuildSheetTag: %s
                cameraPlanSheetTag: %s
                soundTimelineLayers: %s
                googleSoundDesignPrompt: %s
                previousShotContinuityContext: %s
                """.formatted(
                defaultString(editNote, "EMPTY_USER_NOTE: enhance using production design plus continuity context only."),
                writeJson(storyboardTag),
                writeJson(lightingTag),
                writeJson(cameraTag),
                writeJson(soundTimeline),
                writeJson(buildGoogleSoundDesignPrompt(script, plan, take.shotNumber(), soundTimeline)),
                writeJson(continuityContext)
        ).trim();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", prompt);
        payload.put("imagePrompt", imagePrompt);
        payload.put("safeRetryImagePrompt", safeRetryImagePrompt);
        payload.put("mode", "IMAGE_WISE_GEMINI_EDIT");
        payload.put("provider", "gemini");
        payload.put("scriptId", script.getId().toString());
        payload.put("shotNumber", take.shotNumber());
        payload.put("storyboardTag", storyboardTag);
        payload.put("lightingBuildSheetTag", lightingTag);
        payload.put("cameraPlanSheetTag", cameraTag);
        payload.put("soundTimeline", soundTimeline);
        payload.put("previousShotContinuityContext", continuityContext);
        payload.put("continuityReference", continuityReferenceSummary(continuityReference));
        payload.put("googleSoundDesignPrompt", buildGoogleSoundDesignPrompt(script, plan, take.shotNumber(), soundTimeline));
        payload.put("faceIdentityLock", faceIdentityLockPolicy());
        payload.put("editNote", editNote);
        payload.put("editPolicy", Map.of(
                "userEditMode", editNote.isBlank() ? "PRODUCTION_DESIGN_PLUS_CONTINUITY" : "APPEND_USER_NOTE_TO_PRODUCTION_DESIGN",
                "emptyPromptBehavior", "use_production_design_and_previous_shot_continuity",
                "nonEmptyPromptBehavior", "apply_user_note_additively_on_top_of_production_design",
                "preserve", List.of("exact_actor_face_identity", "facial_geometry", "distinguishing_features", "expression", "mouth_shape", "lip_sync", "pose", "framing", "hands", "camera_angle")
        ));
        payload.put("referenceAssetId", referenceImage == null ? null : referenceImage.assetId().toString());
        payload.put("referenceInputRole", referenceImage == null ? "missing" : referenceImage.role());
        payload.put("referenceContentType", referenceImage == null ? take.contentType() : referenceImage.contentType());
        return payload;
    }

    private Map<String, Object> buildVeoPromptPayload(
            CreatorScript script,
            CreatorScriptShotPlan plan,
            ShotTakeData take,
            EnhanceAllShotTakesRequest request,
            ReferenceImage sourceImage
    ) {
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        Map<String, Object> lightingTag = plan == null ? Map.of() : safeMap(plan.getLightingBuildSheetTag());
        Map<String, Object> cameraTag = plan == null ? Map.of() : safeMap(plan.getCameraPlanSheetTag());
        List<Map<String, Object>> soundTimeline = buildSoundTimeline(script, plan, take.shotNumber());
        Map<String, Object> continuityContext = buildPreviousShotContinuityContext(script.getId(), take.shotNumber());
        String editNote = defaultString(request == null ? null : request.editNote(), "");
        String prompt = """
                Generate a premium polished creator video from the supplied reference frame.

                Critical preservation rules:
                - Keep the exact same actor face and identity from the reference frame. Do not change facial geometry, age, ethnicity, skin tone identity, eye shape/color, nose, lips, jawline, face width, scars, moles, teeth, or any distinguishing facial feature.
                - Clothes, environment, set design, lighting, grading, hair styling, and surface-level makeup may change if requested, but the actual face must remain intact.
                - Makeup/beauty enhancement must be natural surface polish only: no face swap, no face morphing, no new face, no age change, no plastic smoothing that changes identity.
                - Keep outfit continuity unless the user explicitly requests a clothing change. Keep body type, pose, framing, shot scale, camera angle, lens feel, and main foreground geometry from the reference frame.
                - Preserve the intended dialogue/performance beat. If native audio is generated, make dialogue natural and intelligible, then mix ambience, foley, and sync hits around it.
                - Do not add text overlays, watermarks, logos, extra people, extra limbs, or distracting foreground objects.

                Upgrade goals:
                - Make the video feel glossy, cinematic, and client-ready.
                - Improve lighting quality, color grade, shadows, background polish, production design, set dressing, and perceived camera quality.
                - Keep motion realistic for a short creator/social ad shot; avoid surreal camera moves.
                - Use native Google/Veo audio: ambience, foley, sync hits, room tone, and subtle sound bed from the timeline. Do not overpower the actor voice.
                - Always start from production design JSON, lightingBuildSheetTag, cameraPlanSheetTag, soundTimeline, and previousShotContinuityContext.
                - User edit note is additive, not a replacement. Apply it on top of the production design.
                - If user edit note is empty, do a continuity enhancement using production design plus previousShotContinuityContext.
                - Previous user visual choices in previousShotContinuityContext, such as wardrobe/look changes, should carry forward unless the current user edit note or hard current shot reference conflicts.
                - Current shot reference frame is the source of truth for face identity, expression, mouth/lip-sync, pose, framing, and hands.

                User edit note to append: %s

                storyboardTag: %s
                lightingBuildSheetTag: %s
                cameraPlanSheetTag: %s
                soundTimelineLayers: %s
                googleSoundDesignPrompt: %s
                previousShotContinuityContext: %s
                """.formatted(
                defaultString(editNote, "EMPTY_USER_NOTE: enhance using production design plus continuity context only."),
                writeJson(storyboardTag),
                writeJson(lightingTag),
                writeJson(cameraTag),
                writeJson(soundTimeline),
                writeJson(buildGoogleSoundDesignPrompt(script, plan, take.shotNumber(), soundTimeline)),
                writeJson(continuityContext)
        ).trim();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", prompt);
        payload.put("mode", "GOOGLE_VEO_IMAGE_TO_VIDEO_POLISH");
        payload.put("provider", "google_veo");
        payload.put("model", properties.getAi().getGeminiVideoModel());
        payload.put("resolution", properties.getAi().getGeminiVideoResolution());
        payload.put("durationSeconds", properties.getAi().getGeminiVideoDurationSeconds());
        payload.put("nativeAudioRequested", true);
        payload.put("scriptId", script.getId().toString());
        payload.put("shotNumber", take.shotNumber());
        payload.put("storyboardTag", storyboardTag);
        payload.put("lightingBuildSheetTag", lightingTag);
        payload.put("cameraPlanSheetTag", cameraTag);
        payload.put("soundTimeline", soundTimeline);
        payload.put("previousShotContinuityContext", continuityContext);
        payload.put("googleSoundDesignPrompt", buildGoogleSoundDesignPrompt(script, plan, take.shotNumber(), soundTimeline));
        payload.put("faceIdentityLock", faceIdentityLockPolicy());
        payload.put("editPolicy", Map.of(
                "userEditMode", editNote.isBlank() ? "PRODUCTION_DESIGN_PLUS_CONTINUITY" : "APPEND_USER_NOTE_TO_PRODUCTION_DESIGN",
                "emptyPromptBehavior", "use_production_design_and_previous_shot_continuity",
                "nonEmptyPromptBehavior", "apply_user_note_additively_on_top_of_production_design",
                "preserve", List.of("exact_actor_face_identity", "facial_geometry", "distinguishing_features", "expression", "mouth_shape", "lip_sync", "pose", "framing", "hands", "camera_angle")
        ));
        payload.put("editNote", editNote);
        payload.put("sourceAssetId", sourceImage == null ? null : sourceImage.assetId().toString());
        payload.put("sourceInputRole", sourceImage == null ? "missing" : sourceImage.role());
        payload.put("sourceContentType", sourceImage == null ? null : sourceImage.contentType());
        return payload;
    }

    private ReferenceImage resolveStudioVeoSourceImage(ShotTakeData take) {
        ReferenceImage selectedFrame = resolveReferenceImage(take);
        if (selectedFrame != null) {
            return selectedFrame;
        }
        return loadPreviewImageSource(take.id(), null, "latest_polished_preview_fallback");
    }

    private Map<String, Object> buildStudioVeoPromptPayload(
            UUID jobId,
            CreatorScript script,
            CreatorScriptShotPlan plan,
            ShotTakeData take,
            ShotTakeStudioPolishRequest request,
            ReferenceImage sourceImage
    ) {
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        Map<String, Object> lightingTag = plan == null ? Map.of() : safeMap(plan.getLightingBuildSheetTag());
        Map<String, Object> cameraTag = plan == null ? Map.of() : safeMap(plan.getCameraPlanSheetTag());
        List<Map<String, Object>> soundTimeline = buildSoundTimeline(script, plan, take.shotNumber());
        Map<String, Object> continuityContext = buildPreviousShotContinuityContext(script.getId(), take.shotNumber());
        String editNote = defaultString(request == null ? null : request.editNote(), "");
        String preset = resolveStudioPolishPreset(request);
        Map<String, Object> controls = resolveStudioPolishControls(request, preset);
        String shotSize = inferShotSize(storyboardTag, cameraTag);
        Map<String, Object> studioLook = buildStudioLookRecipe(storyboardTag, lightingTag, cameraTag, shotSize, editNote, preset, controls);
        Map<String, Object> faceIdentityLock = faceIdentityLockPolicy();
        String provider = resolveStudioPolishVideoProvider(request);
        String model = resolveStudioPolishVideoModel(provider, request);
        String providerMode = resolveStudioPolishVideoMode(provider, request);
        String providerLabel = studioPolishProviderLabel(provider);
        Long providerSeed = resolveStudioPolishProviderSeed(provider, take.id(), request);

        Map<String, Object> productionDesignContext = new LinkedHashMap<>();
        productionDesignContext.put("studioLookIsCanonicalProductionDesign", true);
        productionDesignContext.put("studioLookPreset", preset);
        productionDesignContext.put("studioLookRecipe", studioLook);
        productionDesignContext.put("studioPolishControls", controls);
        productionDesignContext.put("faceIdentityLock", faceIdentityLock);
        productionDesignContext.put("storyboardProductionDesign", firstText(
                storyboardTag.get("productionDesign"),
                storyboardTag.get("setDesign"),
                storyboardTag.get("backgroundDescription"),
                storyboardTag.get("location")
        ));
        productionDesignContext.put("lightingDesign", lightingTag);
        productionDesignContext.put("cameraDesign", cameraTag);
        productionDesignContext.put("soundDesign", soundTimeline);

        Map<String, Object> continuityContextForNextShot = buildVeoContinuityContextForNextShot(
                script,
                take,
                preset,
                editNote,
                studioLook,
                controls,
                storyboardTag,
                lightingTag,
                cameraTag,
                soundTimeline
        );

        String prompt = """
                Render a premium Studio Polish video for this creator shot.

                Source handling:
                - Use the uploaded take as the real performance source.
                - Use the selected timeline/reference frame as visual source of truth for face, expression, mouth shape, pose, hands, framing, camera angle, lens feel, and shot scale.
                - Face identity is locked. Preserve the exact actor face from the reference frame.
                - Do not change facial geometry, age, ethnicity, skin tone identity, eye shape/color, nose, lips, jawline, face width, scars, moles, teeth, or any distinguishing facial feature.
                - Clothes, environment, set design, lighting, grading, hair styling, and surface-level makeup may change if requested, but the actual face must remain intact.
                - Makeup/beauty enhancement must be natural surface polish only: no face swap, no face morphing, no new face, no age change, no plastic smoothing that changes identity.
                - Preserve lip-sync intent, expression, body shape, wardrobe continuity unless changed by user, and action timing.
                - Do not add subtitles, captions, watermarks, logos, extra people, extra limbs, fake hands, or distracting foreground objects.

                Production design:
                - Studio Look is already part of the production design. Treat it as canonical.
                - Start from storyboardTag, lightingBuildSheetTag, cameraPlanSheetTag, soundTimeline, and Studio Look.
                - Use the video model primarily for production design, background/set improvement, lighting design, color grade, perceived camera quality, and subtle actor look polish.
                - Makeup must feel natural and camera-ready: reduce tiredness/harsh shadows/shine only; do not create glam makeup, plastic skin, or a different face.
                - Keep it realistic for a creator video, premium but not plastic, over-generated, over-beautified, or artificial.

                User prompt policy:
                - User prompt is appended on top of the production design, not used as a full replacement.
                - If the user prompt conflicts with production design, the user prompt wins only for the conflicting detail.
                - Preserve every non-conflicting production design, lighting, camera, sound, Studio Look, and continuity element.
                - If the user prompt is empty, enhance using production design plus previous continuity context.
                - If the user asks for a wardrobe/look change, carry that forward into continuityContextForNextShot unless a later shot overrides it.

                Continuity:
                - Use previousShotContinuityContext only as continuity memory.
                - Current shot reference frame and current shot design always win.
                - Preserve continuity of the same actor face, actor look, wardrobe, lighting language, color grade, background/set family, and sound tone across shots.

                Audio:
                - Video providers are used for visual polish. Do not rely on this step for studio-grade audio cleanup.
                - Keep actor voice/dialogue clear and forward.
                - Audio cleanup, generated foley/music, and final mix happen in the separate Dalaillama audio path.

                User prompt to append: %s

                productionDesignContext: %s
                faceIdentityLock: %s
                storyboardTag: %s
                lightingBuildSheetTag: %s
                cameraPlanSheetTag: %s
                soundTimelineLayers: %s
                googleSoundDesignPrompt: %s
                previousShotContinuityContext: %s
                continuityContextForNextShotToPreserve: %s
                """.formatted(
                editNote.isBlank() ? "EMPTY_USER_PROMPT: use production design, Studio Look, and previous continuity only." : editNote,
                writeJson(productionDesignContext),
                writeJson(faceIdentityLock),
                writeJson(storyboardTag),
                writeJson(lightingTag),
                writeJson(cameraTag),
                writeJson(soundTimeline),
                writeJson(buildGoogleSoundDesignPrompt(script, plan, take.shotNumber(), soundTimeline)),
                writeJson(continuityContext),
                writeJson(continuityContextForNextShot)
        ).trim();
        int providerPromptBudgetChars = studioPolishProviderPromptBudgetChars(provider);
        String deterministicProviderPrompt = buildCompactStudioPolishProviderPrompt(
                editNote,
                productionDesignContext,
                faceIdentityLock,
                storyboardTag,
                lightingTag,
                cameraTag,
                continuityContext,
                continuityContextForNextShot,
                providerPromptBudgetChars
        );
        ProviderPromptCompilation promptCompilation = compactStudioPolishProviderPrompt(
                jobId,
                script,
                take,
                provider,
                providerLabel,
                model,
                prompt,
                deterministicProviderPrompt,
                providerPromptBudgetChars
        );
        String providerPrompt = promptCompilation.prompt();
        if ("decart".equals(provider)) {
            providerPrompt = buildDecartWardrobeTryOnPrompt(editNote, storyboardTag, lightingTag, providerPrompt);
        }

        Map<String, Object> providerTask = new LinkedHashMap<>();
        providerTask.put("taskType", "STUDIO_POLISH_VIDEO_TO_VIDEO");
        providerTask.put("version", "studio-polish-video-v2");
        providerTask.put("provider", provider);
        providerTask.put("providerLabel", providerLabel);
        providerTask.put("model", model);
        providerTask.put("providerMode", providerMode);
        if (providerSeed != null) {
            providerTask.put("seed", providerSeed);
            providerTask.put("seedPolicy", "runway_seed_is_stable_for_progressive_refinement");
        }
        providerTask.put("takeId", take.id().toString());
        providerTask.put("scriptId", script.getId().toString());
        providerTask.put("projectId", take.projectId() == null ? null : take.projectId().toString());
        providerTask.put("shotNumber", take.shotNumber());
        providerTask.put("sourceAssetId", take.assetId().toString());
        providerTask.put("sourceMediaUrl", signedUrl(take.bucket(), take.objectKey(), ""));
        providerTask.put("sourceContentType", take.contentType());
        providerTask.put("sourceMediaPreflight", "runway".equals(provider)
                ? "backend_transcodes_to_30fps_cfr_h264_aac_mp4_before_provider_call"
                : "provider_native_input");
        providerTask.put("referenceAssetId", sourceImage == null ? null : sourceImage.assetId().toString());
        providerTask.put("referenceInputRole", sourceImage == null ? "missing" : sourceImage.role());
        providerTask.put("referenceContentType", sourceImage == null ? null : sourceImage.contentType());
        providerTask.put("screenType", stringValue(script.getScreenType(), "vertical"));
        providerTask.put("providerDurationSeconds", properties.getAi().getGeminiVideoDurationSeconds());
        providerTask.put("targetShotDurationSeconds", shotDurationSeconds(script, take.shotNumber()));
        providerTask.put("resolution", properties.getAi().getGeminiVideoResolution());
        providerTask.put("nativeAudioRequested", "google_veo".equals(provider));
        providerTask.put("audioPolicy", "video_provider_visual_only_audio_cleanup_and_mix_are_separate");
        providerTask.put("prompt", providerPrompt);
        providerTask.put("fullPromptContext", prompt);
        providerTask.put("promptCompression", promptCompilation.metadata());
        providerTask.put("productionDesignContext", productionDesignContext);
        providerTask.put("faceIdentityLock", faceIdentityLock);
        providerTask.put("previousShotContinuityContext", continuityContext);
        providerTask.put("continuityContextForNextShot", continuityContextForNextShot);
        providerTask.put("guardrails", List.of(
                "production_design_first",
                "user_prompt_appended_to_production_design",
                "user_prompt_overrides_conflicting_details_only",
                "preserve_all_non_conflicting_production_design_lighting_camera_sound_and_studio_look",
                "face_identity_locked_no_facial_geometry_or_distinguishing_feature_change",
                "makeup_and_beauty_surface_polish_only_no_face_morphing",
                "clothing_environment_lighting_can_change_when_requested",
                "preserve_actor_identity_face_expression_mouth_shape_lip_sync_pose_hands",
                "carry_forward_wardrobe_or_look_changes_to_next_shot_context",
                "no_text_logo_watermark_extra_people_or_extra_limbs",
                "current_shot_reference_frame_is_source_of_truth"
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", prompt);
        payload.put("providerPrompt", providerPrompt);
        payload.put("deterministicProviderPrompt", deterministicProviderPrompt);
        payload.put("promptCompression", promptCompilation.metadata());
        payload.put("mode", "STUDIO_POLISH_VIDEO_TO_VIDEO");
        payload.put("provider", provider);
        payload.put("providerLabel", providerLabel);
        payload.put("model", model);
        payload.put("providerMode", providerMode);
        if (providerSeed != null) {
            payload.put("seed", providerSeed);
            payload.put("seedPolicy", "runway_seed_is_stable_for_progressive_refinement");
        }
        payload.put("resolution", properties.getAi().getGeminiVideoResolution());
        payload.put("durationSeconds", properties.getAi().getGeminiVideoDurationSeconds());
        payload.put("nativeAudioRequested", "google_veo".equals(provider));
        payload.put("audioPolicy", "video_provider_visual_only_audio_cleanup_and_mix_are_separate");
        payload.put("scriptId", script.getId().toString());
        payload.put("shotNumber", take.shotNumber());
        payload.put("takeId", take.id().toString());
        payload.put("sourceAssetId", take.assetId().toString());
        payload.put("sourceContentType", take.contentType());
        payload.put("sourceMediaPreflight", providerTask.get("sourceMediaPreflight"));
        payload.put("referenceAssetId", sourceImage == null ? null : sourceImage.assetId().toString());
        payload.put("referenceInputRole", sourceImage == null ? "missing" : sourceImage.role());
        payload.put("referenceContentType", sourceImage == null ? null : sourceImage.contentType());
        payload.put("screenType", stringValue(script.getScreenType(), "vertical"));
        payload.put("storyboardTag", storyboardTag);
        payload.put("lightingBuildSheetTag", lightingTag);
        payload.put("cameraPlanSheetTag", cameraTag);
        payload.put("soundTimeline", soundTimeline);
        payload.put("googleSoundDesignPrompt", buildGoogleSoundDesignPrompt(script, plan, take.shotNumber(), soundTimeline));
        payload.put("studioLookPreset", preset);
        payload.put("studioLookRecipe", studioLook);
        payload.put("studioPolishControls", controls);
        payload.put("productionDesignContext", productionDesignContext);
        payload.put("faceIdentityLock", faceIdentityLock);
        payload.put("previousShotContinuityContext", continuityContext);
        payload.put("continuityContextForNextShot", continuityContextForNextShot);
        payload.put("editNote", editNote);
        payload.put("editPolicy", Map.of(
                "userEditMode", editNote.isBlank() ? "PRODUCTION_DESIGN_STUDIO_LOOK_PLUS_CONTINUITY" : "APPEND_USER_PROMPT_TO_PRODUCTION_DESIGN_STUDIO_LOOK",
                "emptyPromptBehavior", "use_production_design_studio_look_and_previous_shot_continuity",
                "nonEmptyPromptBehavior", "apply_user_prompt_additively_on_top_of_production_design_and_studio_look",
                "conflictResolution", "user_prompt_overrides_only_the_conflicting_detail_preserve_everything_else",
                "preserve", List.of("exact_actor_face_identity", "facial_geometry", "distinguishing_features", "expression", "mouth_shape", "lip_sync", "pose", "framing", "hands", "camera_angle")
        ));
        payload.put("providerTask", providerTask);
        payload.put("overrides", request == null || request.overrides() == null ? Map.of() : request.overrides());
        return payload;
    }

    private String buildCompactStudioPolishProviderPrompt(
            String editNote,
            Map<String, Object> productionDesignContext,
            Map<String, Object> faceIdentityLock,
            Map<String, Object> storyboardTag,
            Map<String, Object> lightingTag,
            Map<String, Object> cameraTag,
            Map<String, Object> previousShotContinuityContext,
            Map<String, Object> continuityContextForNextShot,
            int maxLength
    ) {
        String production = firstText(
                productionDesignContext.get("storyboardProductionDesign"),
                storyboardTag.get("productionDesign"),
                storyboardTag.get("setDesign"),
                storyboardTag.get("backgroundDescription"),
                "premium realistic creator studio"
        );
        String lighting = firstText(
                lightingTag.get("keyLight"),
                lightingTag.get("lightingSetup"),
                lightingTag.get("mood"),
                "soft cinematic key light, controlled shadows, subtle rim light"
        );
        String camera = firstText(
                cameraTag.get("cameraAngle"),
                cameraTag.get("shotSize"),
                cameraTag.get("movement"),
                "preserve original camera angle, framing, movement, timing, and lens feel"
        );
        String userNote = defaultString(editNote, "").isBlank()
                ? "No extra user prompt. Improve using production design and continuity only."
                : shortText(editNote, 220);
        return shortText("""
                Edit this uploaded creator video visually. Preserve the same actor identity, exact face, expression, lip sync, motion, hands, body timing, and camera framing. Do not face-swap or change facial geometry. Improve production design, background/set, lighting design, shadows, color grade, perceived camera quality, and natural camera-ready makeup. Makeup must be subtle and realistic: reduce shine/tiredness/harsh shadows only, no glam makeover, no plastic smoothing, no identity change. Wardrobe can change only if requested. Production design: %s. Lighting: %s. Camera: %s. User prompt appended: %s. Carry continuity from previous shots: %s. Keep non-conflicting production design and only let the user prompt override directly conflicting details. No text, logos, watermarks, extra people, fake limbs, or plastic AI look. This video pass is visual only; audio cleanup and final mix happen separately.
                """.formatted(
                shortText(production, 180),
                shortText(lighting, 140),
                shortText(camera, 140),
                userNote,
                shortText(writeJson(Map.of(
                        "faceIdentityLock", faceIdentityLock,
                        "previous", previousShotContinuityContext,
                        "next", continuityContextForNextShot
                )), 260)
        ), Math.max(500, maxLength));
    }

    private String buildDecartWardrobeTryOnPrompt(
            String editNote,
            Map<String, Object> storyboardTag,
            Map<String, Object> lightingTag,
            String fallbackPrompt
    ) {
        String wardrobe = defaultString(editNote, "").trim();
        if (wardrobe.isBlank()) {
            wardrobe = firstText(
                    storyboardTag.get("wardrobe"),
                    storyboardTag.get("costume"),
                    "a clean, camera-ready neutral creator top that fits the existing shot"
            );
        }
        String normalized = wardrobe.toLowerCase(Locale.ROOT);
        String actionPrompt = normalized.startsWith("substitute")
                || normalized.startsWith("replace")
                || normalized.startsWith("add ")
                ? wardrobe
                : "Substitute the current visible clothing with " + wardrobe;
        return shortText("""
                %s. Preserve the same actor face, identity, expression, pose, body motion, hand motion, mouth movement, lip sync, camera angle, framing, background, production design, and lighting. Keep the result realistic, natural, and creator-video ready. Do not change the face, age, ethnicity, body shape, scene, camera, text, logo, or add people. Lighting note to preserve: %s. Fallback context: %s
                """.formatted(
                actionPrompt,
                shortText(writeJson(lightingTag), 220),
                shortText(fallbackPrompt, 220)
        ).replaceAll("\\s+", " ").trim(), 900);
    }

    private ProviderPromptCompilation compactStudioPolishProviderPrompt(
            UUID jobId,
            CreatorScript script,
            ShotTakeData take,
            String provider,
            String providerLabel,
            String model,
            String fullPrompt,
            String deterministicProviderPrompt,
            int maxLength
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("enabled", properties.getAi().isStudioPolishPromptCompactionEnabled());
        metadata.put("provider", provider);
        metadata.put("providerLabel", providerLabel);
        metadata.put("model", model);
        metadata.put("budgetChars", maxLength);
        metadata.put("fallbackPromptLength", deterministicProviderPrompt == null ? 0 : deterministicProviderPrompt.length());
        metadata.put("strategy", "llm_json_compaction_with_deterministic_fallback");
        if (!properties.getAi().isStudioPolishPromptCompactionEnabled()) {
            metadata.put("source", "deterministic_disabled");
            return new ProviderPromptCompilation(shortText(deterministicProviderPrompt, maxLength), metadata);
        }

        Map<String, Object> compactionInput = new LinkedHashMap<>();
        compactionInput.put("renderedPrompt", """
                Compact this Studio Polish video-edit prompt for the downstream video model.

                Return only a valid JSON object with:
                {
                  "compactPrompt": "single dense provider prompt",
                  "preservedFacts": ["short list of important details preserved"],
                  "omissionRisk": "short note"
                }

                Rules:
                - compactPrompt must be <= %d characters.
                - Do not omit actor identity lock, exact face preservation, expression, mouth shape, lip sync, hands, body timing, current shot reference, production design, Studio Look, lighting, camera, continuity, and user prompt conflict policy.
                - User prompt is appended to production design. It overrides only directly conflicting details.
                - If the user prompt is empty, preserve production design plus previous-shot continuity.
                - Preserve all non-conflicting production, lighting, camera, sound, and continuity details.
                - Prefer dense clauses separated by semicolons. No markdown.
                - If there is too much context, prioritize: identity guard, current shot action/framing, user prompt, production design, lighting, camera, continuity, forbidden changes.

                Provider: %s
                Model: %s
                ScriptId: %s
                ShotNumber: %d

                Full prompt to compact:
                %s

                Deterministic fallback prompt:
                %s
                """.formatted(
                maxLength,
                providerLabel,
                model,
                script.getId(),
                take.shotNumber(),
                fullPrompt,
                deterministicProviderPrompt
        ));
        compactionInput.put("provider", provider);
        compactionInput.put("model", model);
        compactionInput.put("scriptId", script.getId().toString());
        compactionInput.put("projectId", take.projectId() == null ? null : take.projectId().toString());
        compactionInput.put("takeId", take.id().toString());
        compactionInput.put("shotNumber", take.shotNumber());
        compactionInput.put("budgetChars", maxLength);

        try {
            CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                    take.tenantId(),
                    take.userId(),
                    take.projectId(),
                    jobId,
                    null
            );
            CreatorAiService.MeteredAiResponse response = creatorAiService.generateMetered(
                    "STUDIO_POLISH_PROMPT_COMPACT",
                    compactionInput,
                    usageContext
            );
            creatorAiService.publishBillingDebit("STUDIO_POLISH_PROMPT_COMPACT", response, usageContext);
            Map<String, Object> output = response.output() == null ? Map.of() : response.output();
            String compactPrompt = normalizeCompactProviderPrompt(firstText(
                    output.get("compactPrompt"),
                    output.get("compressedPrompt"),
                    output.get("providerPrompt"),
                    output.get("prompt"),
                    output.get("rawText")
            ));
            if (compactPrompt.isBlank()) {
                metadata.put("source", "deterministic_empty_llm_output");
                metadata.put("llmOutput", output);
                metadata.put("tokenMetadata", response.tokenMetadata());
                metadata.put("costMetadata", response.costMetadata());
                return new ProviderPromptCompilation(shortText(deterministicProviderPrompt, maxLength), metadata);
            }
            compactPrompt = enforceStudioPolishPromptGuardrails(compactPrompt, maxLength);
            metadata.put("source", "llm_compacted");
            metadata.put("promptLength", compactPrompt.length());
            metadata.put("llmOutput", output);
            metadata.put("tokenMetadata", response.tokenMetadata());
            metadata.put("costMetadata", response.costMetadata());
            return new ProviderPromptCompilation(compactPrompt, metadata);
        } catch (RuntimeException ex) {
            log.warn("Studio Polish prompt compaction failed; using deterministic fallback jobId={} takeId={} provider={} error={}",
                    jobId, take.id(), provider, shortText(ex.getMessage(), 500));
            metadata.put("source", "deterministic_compaction_failed");
            metadata.put("errorType", ex.getClass().getSimpleName());
            metadata.put("errorMessage", shortText(ex.getMessage(), 500));
            return new ProviderPromptCompilation(shortText(deterministicProviderPrompt, maxLength), metadata);
        }
    }

    private int studioPolishProviderPromptBudgetChars(String provider) {
        String normalized = defaultString(provider, "google_veo").toLowerCase(Locale.ROOT);
        if ("runway".equals(normalized)) {
            return Math.max(500, properties.getAi().getStudioPolishPromptCompactionRunwayChars());
        }
        if ("decart".equals(normalized)) {
            return Math.max(500, properties.getAi().getStudioPolishPromptCompactionRunwayChars());
        }
        if ("luma".equals(normalized)) {
            return Math.max(800, properties.getAi().getStudioPolishPromptCompactionLumaChars());
        }
        return Math.max(800, properties.getAi().getStudioPolishPromptCompactionGoogleVeoChars());
    }

    private String normalizeCompactProviderPrompt(String value) {
        String text = defaultString(value, "").trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String enforceStudioPolishPromptGuardrails(String compactPrompt, int maxLength) {
        String identityGuard = "Preserve exact actor face identity, facial geometry, expression, mouth shape, lip sync, hands, body timing, and current shot framing; ";
        String text = compactPrompt;
        String lower = compactPrompt.toLowerCase(Locale.ROOT);
        if (!lower.contains("face") || !lower.contains("lip")) {
            text = identityGuard + compactPrompt;
        }
        return shortText(text, maxLength);
    }

    private Map<String, Object> buildVeoContinuityContextForNextShot(
            CreatorScript script,
            ShotTakeData take,
            String preset,
            String editNote,
            Map<String, Object> studioLook,
            Map<String, Object> controls,
            Map<String, Object> storyboardTag,
            Map<String, Object> lightingTag,
            Map<String, Object> cameraTag,
            List<Map<String, Object>> soundTimeline
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("version", "studio-polish-veo-continuity-v1");
        context.put("scriptId", script.getId().toString());
        context.put("takeId", take.id().toString());
        context.put("shotNumber", take.shotNumber());
        context.put("mode", "STUDIO_POLISH_VEO");
        context.put("studioLookPreset", preset);
        context.put("studioLookRecipe", studioLook);
        context.put("studioPolishControls", controls);
        context.put("faceIdentityLock", faceIdentityLockPolicy());
        context.put("userPromptApplied", defaultString(editNote, ""));
        context.put("wardrobeOrLookInstruction", extractWardrobeOrLookInstruction(editNote));
        context.put("productionDesign", firstText(
                storyboardTag.get("productionDesign"),
                storyboardTag.get("setDesign"),
                storyboardTag.get("backgroundDescription"),
                storyboardTag.get("location")
        ));
        context.put("lightingDesign", lightingTag);
        context.put("cameraDesign", cameraTag);
        context.put("soundDesign", soundTimeline);
        context.put("carryForwardRules", List.of(
                "carry_forward_user_prompt_wardrobe_and_actor_look_changes_when_next_prompt_is_empty",
                "never_carry_forward_a_changed_face_identity",
                "face_identity_always_comes_from_current_reference_frame",
                "keep_studio_look_color_grade_lighting_language_and_set_family_consistent",
                "next_shot_current_reference_frame_and_current_production_design_still_win",
                "do_not_copy_composition_if_next_shot_camera_plan_differs"
        ));
        return context;
    }

    private Map<String, Object> faceIdentityLockPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("enabled", true);
        policy.put("sourceOfTruth", "current_shot_reference_frame_or_uploaded_take");
        policy.put("locked", List.of(
                "facial_geometry",
                "age_identity",
                "ethnicity_identity",
                "skin_tone_identity",
                "eye_shape_and_color",
                "nose_shape",
                "lip_shape",
                "jawline",
                "face_width",
                "teeth",
                "moles_scars_birthmarks",
                "distinguishing_facial_features"
        ));
        policy.put("allowedChanges", List.of(
                "clothing_when_user_requests",
                "environment_and_set_design",
                "lighting_and_shadow_quality",
                "cinematic_color_grade",
                "hair_styling_without_identity_change",
                "natural_surface_makeup",
                "subtle_skin_cleanup_without_geometry_change",
                "face_brightness_and_clarity"
        ));
        policy.put("forbiddenChanges", List.of(
                "face_swap",
                "new_face",
                "face_morphing",
                "age_change",
                "ethnicity_change",
                "skin_tone_identity_change",
                "beauty_filter_that_changes_bone_structure",
                "plastic_smoothing_that_erases_identity",
                "changing_eye_nose_lips_jaw_or_teeth"
        ));
        policy.put("instruction", "You may change clothes, environment, set design, lighting, grading, hair styling, and natural makeup. Do not change the actor's actual face at all.");
        return policy;
    }

    private String extractWardrobeOrLookInstruction(String editNote) {
        String note = defaultString(editNote, "").trim();
        if (note.isBlank()) {
            return "";
        }
        String normalized = note.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "wear", "wardrobe", "shirt", "top", "jacket", "dress", "makeup", "hair", "look", "glasses", "hat")) {
            return note;
        }
        return "";
    }

    private StudioPolishVideoGenerationService.GeneratedVideo generateStudioPolishVideo(
            CreatorScript script,
            ShotTakeData take,
            ReferenceImage sourceImage,
            Map<String, Object> promptPayload
    ) {
        byte[] referenceBytes = sourceImage == null ? null : assetStorageService.s3Client()
                .getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(sourceImage.bucket())
                        .key(sourceImage.objectKey())
                        .build())
                .asByteArray();
        String provider = studioPolishVideoGenerationService.normalizeProvider(stringValue(promptPayload.get("provider"), ""));
        SourceVideoData sourceVideo = resolveStudioPolishSourceVideo(take);
        String sourceVideoUrl = signedUrl(sourceVideo.bucket(), sourceVideo.objectKey(), "");
        String sourceVideoContentType = sourceVideo.contentType();
        Map<String, Object> sourceVideoNormalization = Map.of();
        String providerPrompt = firstText(promptPayload.get("providerPrompt"), promptPayload.get("prompt"));
        promptPayload.put("sourceMediaUrl", sourceVideoUrl);
        promptPayload.put("sourceMediaUrlRole", sourceVideo.role());
        promptPayload.put("sourceVideoAssetId", sourceVideo.assetId().toString());
        promptPayload.put("sourceVideoContentType", sourceVideoContentType);
        updateProviderTaskWithSourceInput(promptPayload, sourceVideoUrl, sourceVideo.role(), sourceVideo.assetId(), sourceVideoContentType);
        log.info(
                "Studio Polish provider generation preparing provider={} model={} providerMode={} seed={} takeId={} scriptId={} shotNumber={} sourceAssetId={} sourceRole={} sourceContentType={} sourceObjectKey={} referenceAssetId={} referenceBytes={} promptChars={} promptSnippet=\"{}\"",
                provider,
                stringValue(promptPayload.get("model"), ""),
                stringValue(promptPayload.get("providerMode"), ""),
                longValue(promptPayload.get("seed")),
                take.id(),
                script.getId(),
                take.shotNumber(),
                sourceVideo.assetId(),
                sourceVideo.role(),
                sourceVideoContentType,
                sourceVideo.objectKey(),
                sourceImage == null ? null : sourceImage.assetId(),
                referenceBytes == null ? 0 : referenceBytes.length,
                providerPrompt.length(),
                logSnippet(providerPrompt, 900)
        );
        if ("runway".equals(provider)) {
            LocalRunwayVideoNormalizationService.NormalizedVideo normalizedVideo = localRunwayVideoNormalizationService.normalizeForRunway(
                    script.getId(),
                    take.shotNumber(),
                    take.id(),
                    sourceVideo.assetId(),
                    sourceVideo.bucket(),
                    sourceVideo.objectKey(),
                    sourceVideo.contentType()
            );
            sourceVideoUrl = normalizedVideo.signedUrl();
            sourceVideoContentType = normalizedVideo.contentType();
            sourceVideoNormalization = normalizedVideo.metadata();
            promptPayload.put("sourceMediaUrl", sourceVideoUrl);
            promptPayload.put("sourceMediaUrlRole", "runway_normalized_30fps_copy");
            promptPayload.put("runwayInputNormalization", sourceVideoNormalization);
            updateProviderTaskWithRunwayInput(promptPayload, sourceVideoUrl, sourceVideoNormalization);
            log.info(
                    "Studio Polish Runway input normalization applied takeId={} scriptId={} shotNumber={} sourceAssetId={} normalizedObjectKey={} normalizedSizeBytes={} sourceFrameRate={} outputFrameRate={} outputDurationSeconds={}",
                    take.id(),
                    script.getId(),
                    take.shotNumber(),
                    sourceVideo.assetId(),
                    normalizedVideo.objectKey(),
                    normalizedVideo.sizeBytes(),
                    sourceVideoNormalization.get("sourceFrameRate"),
                    sourceVideoNormalization.get("outputFrameRate"),
                    sourceVideoNormalization.get("outputDurationSeconds")
            );
        }

        StudioPolishVideoGenerationService.GeneratedVideo generatedVideo = studioPolishVideoGenerationService.generate(new StudioPolishVideoGenerationService.StudioPolishVideoRequest(
                provider,
                stringValue(promptPayload.get("model"), ""),
                stringValue(promptPayload.get("providerMode"), ""),
                firstText(promptPayload.get("providerPrompt"), promptPayload.get("prompt")),
                sourceVideoUrl,
                sourceVideoContentType,
                sourceImage == null ? "" : signedUrl(sourceImage.bucket(), sourceImage.objectKey(), ""),
                referenceBytes,
                sourceImage == null ? "" : sourceImage.contentType(),
                stringValue(script.getScreenType(), "vertical"),
                stringValue(promptPayload.get("resolution"), properties.getAi().getGeminiVideoResolution()),
                stringValue(promptPayload.get("durationSeconds"), properties.getAi().getGeminiVideoDurationSeconds()),
                take.id().toString(),
                take.shotNumber(),
                longValue(promptPayload.get("seed"))
        ));
        log.info(
                "Studio Polish provider generation completed provider={} model={} operationId={} takeId={} scriptId={} shotNumber={} outputBytes={} outputContentType={}",
                provider,
                stringValue(generatedVideo.metadata().get("model"), stringValue(promptPayload.get("model"), "")),
                generatedVideo.operationName(),
                take.id(),
                script.getId(),
                take.shotNumber(),
                generatedVideo.bytes() == null ? 0 : generatedVideo.bytes().length,
                generatedVideo.contentType()
        );
        Map<String, Object> metadata = new LinkedHashMap<>(generatedVideo.metadata() == null ? Map.of() : generatedVideo.metadata());
        metadata.put("sourceVideoAssetId", sourceVideo.assetId().toString());
        metadata.put("sourceVideoRole", sourceVideo.role());
        metadata.put("sourceVideoObjectKey", sourceVideo.objectKey());
        metadata.put("sourceVideoContentType", sourceVideo.contentType());
        Map<String, Object> providerRequest = new LinkedHashMap<>(generatedVideo.providerRequest() == null ? Map.of() : generatedVideo.providerRequest());
        providerRequest.put("sourceVideoAssetId", sourceVideo.assetId().toString());
        providerRequest.put("sourceVideoRole", sourceVideo.role());
        if (!sourceVideoNormalization.isEmpty()) {
            metadata.put("runwayInputNormalization", sourceVideoNormalization);
            metadata.put("sourceMediaUrlRole", "runway_normalized_30fps_copy");
            providerRequest.put("runwayInputNormalization", sourceVideoNormalization);
            providerRequest.put("sourceMediaUrlRole", "runway_normalized_30fps_copy");
        }
        return new StudioPolishVideoGenerationService.GeneratedVideo(
                generatedVideo.bytes(),
                generatedVideo.contentType(),
                metadata,
                generatedVideo.operationName(),
                providerRequest,
                generatedVideo.providerResponse()
        );
    }

    private SourceVideoData resolveStudioPolishSourceVideo(ShotTakeData take) {
        VariantMediaData latestPolishedVideo = loadLatestFinalVideoMediaData(take.id());
        if (latestPolishedVideo != null
                && latestPolishedVideo.finalVideoAssetId() != null
                && !stringValue(latestPolishedVideo.finalVideoBucket(), "").isBlank()
                && !stringValue(latestPolishedVideo.finalVideoObjectKey(), "").isBlank()) {
            return new SourceVideoData(
                    latestPolishedVideo.finalVideoAssetId(),
                    latestPolishedVideo.finalVideoBucket(),
                    latestPolishedVideo.finalVideoObjectKey(),
                    defaultString(latestPolishedVideo.finalVideoContentType(), "video/mp4"),
                    "latest_polished_video"
            );
        }
        return new SourceVideoData(
                take.assetId(),
                take.bucket(),
                take.objectKey(),
                take.contentType(),
                "uploaded_take"
        );
    }

    private void updateProviderTaskWithRunwayInput(
            Map<String, Object> promptPayload,
            String sourceVideoUrl,
            Map<String, Object> sourceVideoNormalization
    ) {
        Object taskObject = promptPayload.get("providerTask");
        if (!(taskObject instanceof Map<?, ?> rawTask)) {
            return;
        }
        Map<String, Object> providerTask = new LinkedHashMap<>();
        rawTask.forEach((key, value) -> providerTask.put(String.valueOf(key), value));
        providerTask.put("sourceMediaUrl", sourceVideoUrl);
        providerTask.put("sourceMediaUrlRole", "runway_normalized_30fps_copy");
        providerTask.put("runwayInputNormalization", sourceVideoNormalization);
        providerTask.put("sourceMediaPreflight", "backend_transcodes_to_30fps_cfr_h264_aac_mp4_before_provider_call");
        promptPayload.put("providerTask", providerTask);
    }

    private void updateProviderTaskWithSourceInput(
            Map<String, Object> promptPayload,
            String sourceVideoUrl,
            String sourceVideoRole,
            UUID sourceVideoAssetId,
            String sourceVideoContentType
    ) {
        Object taskObject = promptPayload.get("providerTask");
        if (!(taskObject instanceof Map<?, ?> rawTask)) {
            return;
        }
        Map<String, Object> providerTask = new LinkedHashMap<>();
        rawTask.forEach((key, value) -> providerTask.put(String.valueOf(key), value));
        providerTask.put("sourceMediaUrl", sourceVideoUrl);
        providerTask.put("sourceMediaUrlRole", sourceVideoRole);
        providerTask.put("sourceAssetId", sourceVideoAssetId == null ? null : sourceVideoAssetId.toString());
        providerTask.put("sourceVideoAssetId", sourceVideoAssetId == null ? null : sourceVideoAssetId.toString());
        providerTask.put("sourceContentType", sourceVideoContentType);
        providerTask.put("sourceMediaPreflight", "provider_native_input");
        promptPayload.put("providerTask", providerTask);
    }

    private StudioPolishVideoGenerationService.GeneratedVideo generateVeoVideo(
            CreatorScript script,
            ShotTakeData take,
            ReferenceImage sourceImage,
            Map<String, Object> promptPayload
    ) {
        Map<String, Object> googlePayload = new LinkedHashMap<>(promptPayload == null ? Map.of() : promptPayload);
        googlePayload.put("provider", "google_veo");
        googlePayload.put("model", properties.getAi().getGeminiVideoModel());
        return generateStudioPolishVideo(script, take, sourceImage, googlePayload);
    }

    private UUID storeVeoVideoAsset(
            CreatorScript script,
            ShotTakeData take,
            UUID variantId,
            ReferenceImage sourceImage,
            StudioPolishVideoGenerationService.GeneratedVideo generatedVideo
    ) {
        String provider = studioPolishVideoGenerationService.normalizeProvider(stringValue(generatedVideo.metadata().get("provider"), "studio_polish"));
        String objectKey = "shot-enhancements/%s/%02d/%s/%s.mp4".formatted(script.getId(), take.shotNumber(), provider, variantId);
        AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAsset(
                objectKey,
                generatedVideo.bytes(),
                generatedVideo.contentType(),
                SIGNED_URL_TTL
        );

        Map<String, Object> metadata = new LinkedHashMap<>(generatedVideo.metadata());
        metadata.put("scriptId", script.getId().toString());
        metadata.put("shotNumber", take.shotNumber());
        metadata.put("takeId", take.id().toString());
        metadata.put("variantId", variantId.toString());
        metadata.put("sourceAssetId", sourceImage == null ? null : sourceImage.assetId().toString());
        metadata.put("sourceAssetRole", sourceImage == null ? "uploaded_video" : sourceImage.role());
        metadata.put("assetKind", "shot_enhancement_final_video");

        CreatorAsset videoAsset = upsertAsset(CreatorAsset.builder()
                .tenantId(take.tenantId())
                .userId(take.userId())
                .projectId(take.projectId())
                .assetType(ASSET_TYPE_SHOT_ENHANCEMENT_VIDEO)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());
        resetPolishedVideoAnalysis(take.id(), variantId, videoAsset.getId());
        return videoAsset.getId();
    }

    private Map<String, Object> buildAudioMixPayload(
            CreatorScript script,
            CreatorScriptShotPlan plan,
            ShotTakeData take,
            ShotTakeAudioMixRequest request,
            UUID variantId
    ) {
        List<Map<String, Object>> plannedSoundTimeline = buildSoundTimeline(script, plan, take.shotNumber());
        Map<String, Object> existingTimeline = loadEditorSoundTimeline(take.id());
        Map<String, Object> editorSoundTimeline = normalizeSoundTimeline(
                request == null ? null : request.layers(),
                request == null ? null : request.mixSettings(),
                existingTimeline
        );
        List<Map<String, Object>> userSoundLayers = soundLayerList(editorSoundTimeline.get("layers"));
        List<Map<String, Object>> soundTimeline = new ArrayList<>();
        soundTimeline.addAll(plannedSoundTimeline);
        soundTimeline.addAll(userSoundLayers);
        Map<String, Object> mixSettings = normalizeSoundMixSettings(editorSoundTimeline.get("mixSettings"), request == null ? null : request.mixSettings());
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        String provider = resolveAudioMixProvider(request);
        String model = resolveAudioMixModel(request);
        BigDecimal durationSeconds = resolveAudioEnhancementDurationSeconds(script, take);

        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("provider", provider);
        costMetadata.put("model", model);
        costMetadata.put("pricingMode", "local_audio_mix_worker");
        costMetadata.put("totalCost", BigDecimal.ZERO);
        costMetadata.put("billableTotalCost", BigDecimal.ZERO);
        costMetadata.put("currency", "USD");
        costMetadata.put("note", "Local/worker audio mixing does not use AI provider tokens.");

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskType", "SHOT_TAKE_AUDIO_MIX");
        task.put("version", "audio-mix-v1");
        task.put("provider", provider);
        task.put("model", model);
        task.put("takeId", take.id().toString());
        task.put("variantId", variantId.toString());
        task.put("scriptId", script.getId().toString());
        task.put("projectId", take.projectId() == null ? null : take.projectId().toString());
        task.put("shotNumber", take.shotNumber());
        task.put("shotTitle", firstText(storyboardTag.get("shotTitle"), storyboardTag.get("title"), "Shot " + take.shotNumber()));
        task.put("sourceAssetId", take.assetId().toString());
        task.put("sourceMediaUrl", signedUrl(take.bucket(), take.objectKey(), ""));
        task.put("sourceContentType", take.contentType());
        task.put("sourceHandling", isVideoContentType(take.contentType()) ? "extract_original_audio_track_from_video" : "use_uploaded_audio_directly");
        task.put("durationSeconds", durationSeconds);
        task.put("outputContentType", "audio/wav");
        task.put("outputObjectKeyPrefix", "shot-enhancements/%s/%02d/audio-mix".formatted(script.getId(), take.shotNumber()));
        task.put("completionCallback", "/api/v1/creator/storyboards/shots/takes/%s/audio-mix-complete".formatted(take.id()));
        task.put("renderMode", defaultString(request == null ? null : request.renderMode(), "polished_timeline_audio"));
        task.put("soundTimeline", soundTimeline);
        task.put("plannedSoundTimeline", plannedSoundTimeline);
        task.put("userSoundTimeline", editorSoundTimeline);
        task.put("userSoundLayers", userSoundLayers);
        task.put("mixSettings", mixSettings);
        task.put("audioMixStandards", defaultAudioMixStandards());
        task.put("volumeAutomation", mixSettings.get("volumeAutomation"));
        task.put("snippetAssets", soundSnippetAssets(userSoundLayers));
        task.put("costMetadata", costMetadata);
        task.put("guardrails", List.of(
                "preserve_original_dialogue_sync",
                "preserve_original_voice_texture",
                "keep_dialogue_level_consistent",
                "duck_background_music_under_speech",
                "preserve_scene_matched_room_tone",
                "use_sfx_sparingly",
                "match_reverb_to_scene",
                "apply_smooth_audio_fades",
                "apply_user_timeline_layers_only",
                "apply_volume_automation",
                "normalize_loudness_without_changing_performance"
        ));
        if (request != null && request.metadata() != null && !request.metadata().isEmpty()) {
            task.put("metadata", toStringObjectMap(request.metadata()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "AUDIO_MIX_RENDER");
        payload.put("provider", provider);
        payload.put("model", model);
        payload.put("scriptId", script.getId().toString());
        payload.put("shotNumber", take.shotNumber());
        payload.put("sourceAssetId", take.assetId().toString());
        payload.put("sourceContentType", take.contentType());
        payload.put("durationSeconds", durationSeconds);
        payload.put("soundTimeline", soundTimeline);
        payload.put("plannedSoundTimeline", plannedSoundTimeline);
        payload.put("userSoundTimeline", editorSoundTimeline);
        payload.put("mixSettings", mixSettings);
        payload.put("costMetadata", costMetadata);
        payload.put("providerTask", task);
        return payload;
    }

    private Map<String, Object> buildAudioEnhancementPayload(
            CreatorScript script,
            CreatorScriptShotPlan plan,
            ShotTakeData take,
            AudioEnhancementSource source,
            ShotTakeAudioEnhanceRequest request
    ) {
        List<Map<String, Object>> plannedSoundTimeline = buildSoundTimeline(script, plan, take.shotNumber());
        Map<String, Object> editorSoundTimeline = loadEditorSoundTimeline(take.id());
        List<Map<String, Object>> userSoundLayers = soundLayerList(editorSoundTimeline.get("layers"));
        List<Map<String, Object>> soundTimeline = new ArrayList<>();
        soundTimeline.addAll(plannedSoundTimeline);
        soundTimeline.addAll(userSoundLayers);
        Map<String, Object> mixSettings = normalizeSoundMixSettings(editorSoundTimeline.get("mixSettings"), request == null ? null : request.overrides());
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        Map<String, Object> controls = buildAudioEnhancementControls(request);
        String provider = resolveAudioEnhancementProvider(request);
        String model = resolveAudioEnhancementModel(request);
        BigDecimal durationSeconds = resolveAudioEnhancementDurationSeconds(script, take);
        Map<String, Object> sourceMemory = buildAudioEnhancementSourceMemory(source);
        Map<String, Object> costMetadata = "local_ffmpeg".equalsIgnoreCase(provider)
                ? localWorkerCostMetadata(provider, model, "shot_take_audio_enhancement", "Local FFmpeg audio cleanup does not use AI provider tokens.")
                : pricingService.estimateAudioEnhancementCall(
                        provider,
                        model,
                        durationSeconds,
                        "shot_take_audio_enhancement"
                );
        String prompt = """
                Enhance the original recorded sound for a creator shot.

                Primary goal:
                - Make the source sound feel studio-recorded and video-ready.
                - Remove background noise, hiss, rumble, fan/AC wash, traffic wash, clipping harshness, and muddiness by default.
                - Increase clarity, presence, and loudness consistency.

                Non-negotiable preservation rules:
                - Preserve the exact voice or generated sound texture, timbre, pitch, cadence, timing, emotion, and performance.
                - Preserve the exact words and timing. Do not rewrite, regenerate, overdub, translate, voice-convert, or synthesize new dialogue.
                - Remove distracting fan/AC/traffic/room noise. Keep only enough natural tone to avoid robotic artifacts.
                - Do not synthesize new music, foley, ambience, sync hits, reverb, or stylized effects in this pass.
                - If uploaded snippet layers are provided, place only those snippets on the timeline and mix them under the selected priority rule.
                - Keep output duration and sync aligned to the uploaded take.

                Mix target:
                - Dialogue forward and centered.
                - Transparent denoise, light de-reverb, de-essing only if needed.
                - Loudness target around %s LUFS, true peak below -1 dBTP.
                - Respect user mix settings: dialogue, foley, or background music can be marked as the overtaking layer.

                User note: %s

                Source role: %s
                Source asset memory from previous generation/enhancement: %s
                Shot title: %s
                Planned sound timeline context: %s
                User-uploaded sound layers to mix, if any: %s
                Mix settings and ducking priorities: %s
                Combined timeline sent to worker: %s
                Controls: %s
                """.formatted(
                controls.get("loudnessTargetLufs"),
                defaultString(request == null ? null : request.editNote(), "No extra note."),
                source.role(),
                writeJson(sourceMemory),
                firstText(storyboardTag.get("shotTitle"), storyboardTag.get("title"), "Shot " + take.shotNumber()),
                writeJson(plannedSoundTimeline),
                writeJson(userSoundLayers),
                writeJson(mixSettings),
                writeJson(soundTimeline),
                writeJson(controls)
        ).trim();

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskType", "SHOT_TAKE_AUDIO_ENHANCE");
        task.put("version", "audio-enhance-v1");
        task.put("provider", provider);
        task.put("model", model);
        task.put("takeId", take.id().toString());
        task.put("scriptId", script.getId().toString());
        task.put("projectId", take.projectId() == null ? null : take.projectId().toString());
        task.put("shotNumber", take.shotNumber());
        task.put("sourceAssetId", source.assetId().toString());
        task.put("sourceAssetType", source.assetType());
        task.put("sourceRole", source.role());
        task.put("sourceMediaUrl", signedUrl(source.bucket(), source.objectKey(), ""));
        task.put("sourceContentType", source.contentType());
        task.put("sourceMemory", sourceMemory);
        task.put("sourceHandling", isVideoContentType(source.contentType()) ? "extract_original_audio_track_from_video" : "use_audio_asset_directly");
        task.put("durationSeconds", durationSeconds);
        task.put("outputContentType", "audio/wav");
        task.put("outputObjectKeyPrefix", "shot-enhancements/%s/%02d/audio-enhance".formatted(script.getId(), take.shotNumber()));
        task.put("completionCallback", "/api/v1/creator/storyboards/shots/takes/%s/audio-enhance-complete".formatted(take.id()));
        task.put("prompt", prompt);
        task.put("controls", controls);
        task.put("soundTimeline", soundTimeline);
        task.put("plannedSoundTimeline", plannedSoundTimeline);
        task.put("userSoundTimeline", editorSoundTimeline);
        task.put("userSoundLayers", userSoundLayers);
        task.put("mixSettings", mixSettings);
        task.put("snippetAssets", soundSnippetAssets(userSoundLayers));
        task.put("costMetadata", costMetadata);
        task.put("guardrails", List.of(
                "preserve_original_voice_texture",
                "preserve_words_and_lip_sync",
                "no_voice_conversion",
                "no_dialogue_regeneration",
                "no_unrequested_music_or_foley_generation",
                "use_uploaded_snippets_only_for_extra_layers",
                "transparent_cleanup_only"
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "AUDIO_ENHANCE_PRESERVE_TEXTURE");
        payload.put("provider", provider);
        payload.put("model", model);
        payload.put("scriptId", script.getId().toString());
        payload.put("shotNumber", take.shotNumber());
        payload.put("sourceAssetId", source.assetId().toString());
        payload.put("sourceAssetType", source.assetType());
        payload.put("sourceRole", source.role());
        payload.put("sourceContentType", source.contentType());
        payload.put("sourceMemory", sourceMemory);
        payload.put("durationSeconds", durationSeconds);
        payload.put("prompt", prompt);
        payload.put("controls", controls);
        payload.put("soundTimeline", soundTimeline);
        payload.put("plannedSoundTimeline", plannedSoundTimeline);
        payload.put("userSoundTimeline", editorSoundTimeline);
        payload.put("mixSettings", mixSettings);
        payload.put("costMetadata", costMetadata);
        payload.put("providerTask", task);
        return payload;
    }

    private BigDecimal resolveAudioEnhancementDurationSeconds(CreatorScript script, ShotTakeData take) {
        Map<String, Object> analysis = loadTakeMediaAnalysis(take.id());
        Map<String, Object> audio = objectMap(analysis.get("audio"));
        Map<String, Object> video = objectMap(analysis.get("video"));
        double seconds = firstPositiveDecimal(
                audio.get("durationSeconds"),
                audio.get("duration"),
                divideMillis(audio.get("durationMs")),
                divideMillis(audio.get("durationMillis")),
                video.get("durationSeconds"),
                video.get("duration"),
                divideMillis(video.get("durationMs")),
                divideMillis(video.get("durationMillis")),
                analysis.get("durationSeconds"),
                analysis.get("duration")
        );
        if (seconds <= 0) {
            seconds = shotDurationSeconds(script, take.shotNumber());
        }
        return BigDecimal.valueOf(Math.max(1.0, seconds)).setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private AudioEnhancementSource resolveAudioEnhancementSource(ShotTakeData take, ShotTakeAudioEnhanceRequest request) {
        UUID requestedAssetId = request == null ? null : request.sourceAssetId();
        if (requestedAssetId == null || requestedAssetId.equals(take.assetId())) {
            return new AudioEnhancementSource(
                    take.assetId(),
                    ASSET_TYPE_USER_SHOT_TAKE,
                    "uploaded_take",
                    take.contentType(),
                    take.sizeBytes(),
                    take.bucket(),
                    take.objectKey(),
                    Map.of()
            );
        }

        CreatorAsset asset = assetRepository.findById(requestedAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio source asset was not found."));
        if (!Objects.equals(asset.getTenantId(), take.tenantId())
                || !Objects.equals(asset.getUserId(), take.userId())
                || (asset.getProjectId() != null && !Objects.equals(asset.getProjectId(), take.projectId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Audio source asset does not belong to this take.");
        }
        if (!AUDIO_ENHANCEMENT_SOURCE_ASSET_TYPES.contains(defaultString(asset.getAssetType(), ""))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio source asset is not supported for enhancement.");
        }
        if (!isAudioContentType(asset.getContentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected generated source must be an audio asset.");
        }

        Map<String, Object> metadata = safeMap(asset.getMetadata());
        String metadataTakeId = stringValue(metadata.get("takeId"), "");
        if (!metadataTakeId.isBlank() && !metadataTakeId.equals(take.id().toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Audio source asset belongs to another take.");
        }
        String metadataScriptId = stringValue(metadata.get("scriptId"), "");
        if (!metadataScriptId.isBlank() && !metadataScriptId.equals(take.scriptId().toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Audio source asset belongs to another script.");
        }
        Object metadataShotNumber = metadata.get("shotNumber");
        if (metadataShotNumber != null && numberValue(metadataShotNumber, take.shotNumber()) != take.shotNumber()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Audio source asset belongs to another shot.");
        }

        return new AudioEnhancementSource(
                asset.getId(),
                defaultString(asset.getAssetType(), "AUDIO_ASSET"),
                audioSourceRole(asset),
                defaultString(asset.getContentType(), "audio/wav"),
                asset.getSizeBytes(),
                asset.getBucket(),
                asset.getObjectKey(),
                metadata
        );
    }

    private String audioSourceRole(CreatorAsset asset) {
        return switch (defaultString(asset.getAssetType(), "")) {
            case ASSET_TYPE_SHOT_TAKE_GENERATED_SOUND -> "generated_sound_progressive_enhancement";
            case ASSET_TYPE_SHOT_TAKE_ENHANCED_AUDIO -> "previous_enhanced_audio_progressive_pass";
            case ASSET_TYPE_SHOT_TAKE_MIXED_AUDIO -> "previous_mixed_audio_progressive_pass";
            case ASSET_TYPE_SHOT_TAKE_SOUND_SNIPPET -> "uploaded_sound_snippet";
            default -> "uploaded_take";
        };
    }

    private Map<String, Object> buildAudioEnhancementSourceMemory(AudioEnhancementSource source) {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("sourceAssetId", source.assetId().toString());
        memory.put("sourceAssetType", source.assetType());
        memory.put("sourceRole", source.role());
        memory.put("sourceContentType", source.contentType());
        memory.put("sourceSizeBytes", source.sizeBytes());
        Map<String, Object> metadata = source.metadata() == null ? Map.of() : source.metadata();
        copyIfPresent(metadata, memory, "assetKind");
        copyIfPresent(metadata, memory, "source");
        copyIfPresent(metadata, memory, "sourcePrompt");
        copyIfPresent(metadata, memory, "layerType");
        copyIfPresent(metadata, memory, "mood");
        copyIfPresent(metadata, memory, "instrumentation");
        copyIfPresent(metadata, memory, "audioFixes");
        copyIfPresent(metadata, memory, "processingGoal");
        copyIfPresent(metadata, memory, "controls");
        Map<String, Object> providerTask = objectMap(metadata.get("providerTask"));
        if (!providerTask.isEmpty()) {
            Map<String, Object> taskMemory = new LinkedHashMap<>();
            copyIfPresent(providerTask, taskMemory, "lyriaPrompt");
            copyIfPresent(providerTask, taskMemory, "prompt");
            copyIfPresent(providerTask, taskMemory, "negativePrompt");
            copyIfPresent(providerTask, taskMemory, "layerType");
            copyIfPresent(providerTask, taskMemory, "durationSeconds");
            copyIfPresent(providerTask, taskMemory, "guardrails");
            memory.put("previousProviderTask", taskMemory);
        }
        return memory;
    }

    private Map<String, Object> buildAudioEnhancementControls(ShotTakeAudioEnhanceRequest request) {
        Map<String, Object> controls = new LinkedHashMap<>();
        controls.put("noiseReductionStrength", clampValue(request == null ? null : request.noiseReductionStrength(), 0.0, 1.0, 0.78));
        controls.put("clarityBoost", clampValue(request == null ? null : request.clarityBoost(), 0.0, 1.0, 0.62));
        controls.put("deReverbStrength", clampValue(request == null ? null : request.deReverbStrength(), 0.0, 1.0, 0.30));
        controls.put("loudnessTargetLufs", clampValue(request == null ? null : request.loudnessTargetLufs(), -24.0, -10.0, -14.0));
        controls.put("preserveVoiceTexture", request == null || request.preserveVoiceTexture() == null || Boolean.TRUE.equals(request.preserveVoiceTexture()));
        controls.put("preserveRoomTone", request != null && Boolean.TRUE.equals(request.preserveRoomTone()));
        controls.put("fanNoiseRemoval", true);
        controls.put("studioVoiceTarget", true);
        controls.put("neuralDenoiseEnabled", properties.getAi().isAudioNeuralDenoiseEnabled());
        controls.put("neuralDenoiseProvider", properties.getAi().getAudioNeuralDenoiseProvider());
        controls.put("neuralDenoiseFailOnMissing", properties.getAi().isAudioNeuralDenoiseFailOnMissing());
        controls.put("deepFilterCommand", properties.getAi().getDeepFilterCommand());
        controls.put("deepFilterPostFilterEnabled", properties.getAi().isDeepFilterPostFilterEnabled());
        controls.put("deepFilterModelPathConfigured", !defaultString(properties.getAi().getDeepFilterModelPath(), "").isBlank());
        controls.put("rnnoiseCommand", properties.getAi().getRnnoiseCommand());
        controls.put("ffmpegArnndnModelPathConfigured", !defaultString(properties.getAi().getFfmpegArnndnModelPath(), "").isBlank());
        controls.put("progressiveEnhancementAllowed", true);
        controls.put("voiceConversionAllowed", false);
        controls.put("dialogueRewriteAllowed", false);
        controls.put("musicOrFoleyGenerationAllowed", false);
        controls.put("uploadedSnippetMixingAllowed", true);
        if (request != null && request.overrides() != null && !request.overrides().isEmpty()) {
            controls.put("overrides", toStringObjectMap(request.overrides()));
        }
        return controls;
    }

    private Map<String, Object> localWorkerCostMetadata(String provider, String model, String operation, String note) {
        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("provider", provider);
        costMetadata.put("model", model);
        costMetadata.put("operation", operation);
        costMetadata.put("pricingMode", "local_worker");
        costMetadata.put("totalCost", BigDecimal.ZERO);
        costMetadata.put("actualTotalCost", BigDecimal.ZERO);
        costMetadata.put("billableTotalCost", BigDecimal.ZERO);
        costMetadata.put("customerTotalCost", BigDecimal.ZERO);
        costMetadata.put("currency", "USD");
        costMetadata.put("rateUnit", "WORKER_TASK");
        costMetadata.put("note", note);
        return costMetadata;
    }

    private String resolveAudioEnhancementProvider(ShotTakeAudioEnhanceRequest request) {
        return defaultString(request == null ? null : request.provider(), "local_ffmpeg");
    }

    private String resolveAudioEnhancementModel(ShotTakeAudioEnhanceRequest request) {
        return defaultString(request == null ? null : request.model(), "ffmpeg-audio-enhance-v1");
    }

    private String resolveAudioMixProvider(ShotTakeAudioMixRequest request) {
        return defaultString(request == null ? null : request.provider(), "local_ffmpeg");
    }

    private String resolveAudioMixModel(ShotTakeAudioMixRequest request) {
        return defaultString(request == null ? null : request.model(), "ffmpeg-audio-mix-v1");
    }

    private String resolveStudioPolishVideoProvider(ShotTakeStudioPolishRequest request) {
        return studioPolishVideoGenerationService.normalizeProvider(request == null ? null : request.provider());
    }

    private String resolveStudioPolishVideoModel(String provider, ShotTakeStudioPolishRequest request) {
        return studioPolishVideoGenerationService.modelForProvider(provider, request == null ? null : request.model());
    }

    private String resolveStudioPolishVideoMode(String provider, ShotTakeStudioPolishRequest request) {
        return studioPolishVideoGenerationService.modeForProvider(provider, request == null ? null : request.providerMode());
    }

    private boolean studioPolishProviderRequiresReference(String provider) {
        return "google_veo".equals(studioPolishVideoGenerationService.normalizeProvider(provider));
    }

    private String studioPolishProviderLabel(String provider) {
        return switch (studioPolishVideoGenerationService.normalizeProvider(provider)) {
            case "luma" -> "Luma";
            case "runway" -> "Runway";
            case "decart" -> "Decart Lucy VTON";
            case "google_veo" -> "Google Veo";
            default -> defaultString(provider, "Studio Polish provider");
        };
    }

    private Map<String, Object> buildStudioPolishPayload(
            CreatorScript script,
            CreatorScriptShotPlan plan,
            ShotTakeData take,
            ShotTakeStudioPolishRequest request
    ) {
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        Map<String, Object> lightingTag = plan == null ? Map.of() : safeMap(plan.getLightingBuildSheetTag());
        Map<String, Object> cameraTag = plan == null ? Map.of() : safeMap(plan.getCameraPlanSheetTag());
        List<Map<String, Object>> soundTimeline = buildSoundTimeline(script, plan, take.shotNumber());
        Map<String, Object> continuityContext = buildPreviousShotContinuityContext(script.getId(), take.shotNumber());
        int durationSeconds = shotDurationSeconds(script, take.shotNumber());
        String shotSize = inferShotSize(storyboardTag, cameraTag);
        Map<String, Object> copiedRecipe = resolveCopiedStudioTransformationRecipe(take, request);
        String plateMode = firstText(
                request == null ? null : request.plateMode(),
                copiedRecipe.get("plateMode"),
                "clean_background_plate"
        );
        String editNote = defaultString(request == null ? null : request.editNote(), "");
        String preset = resolveStudioPolishPreset(request, copiedRecipe);
        Map<String, Object> controls = resolveStudioPolishControls(request, preset, copiedRecipe);
        Map<String, Object> lookRecipe = buildStudioLookRecipe(storyboardTag, lightingTag, cameraTag, shotSize, editNote, preset, controls);
        applyCopiedStudioLookRecipe(lookRecipe, copiedRecipe, shotSize, editNote);
        List<Map<String, Object>> frameCandidates = buildFrameCandidates(durationSeconds, request == null ? null : request.candidateTimestampsSeconds());

        Map<String, Object> frameSelection = new LinkedHashMap<>();
        frameSelection.put("strategy", "shot_plan_guided_deterministic");
        frameSelection.put("durationSeconds", durationSeconds);
        frameSelection.put("shotSize", shotSize);
        frameSelection.put("candidateFrames", frameCandidates);
        frameSelection.put("scoringRules", buildFrameScoringRules(shotSize, storyboardTag, cameraTag));
        frameSelection.put("avoid", List.of("motion_blur", "blink", "mouth_extreme_if_not_dialogue_peak", "occluded_face", "overexposed_face", "underexposed_face"));

        String platePrompt = buildStudioPlatePrompt(storyboardTag, lightingTag, cameraTag, lookRecipe, continuityContext, plateMode, editNote);
        Map<String, Object> opencvPlan = buildOpenCvPlan(lookRecipe, frameSelection, soundTimeline, controls);
        applyCopiedOpenCvPlan(opencvPlan, copiedRecipe, frameSelection, soundTimeline, controls, lookRecipe);
        Map<String, Object> opencvTransformationRecipe = buildOpenCvTransformationRecipe(lookRecipe, controls, opencvPlan, copiedRecipe);
        Map<String, Object> processorTask = buildStudioProcessorTask(script, take, frameSelection, lookRecipe, opencvPlan, platePrompt, controls, request);
        Map<String, Object> copyableTransformationRecipe = buildCopyableStudioTransformationRecipe(
                take,
                preset,
                plateMode,
                controls,
                lookRecipe,
                opencvPlan,
                opencvTransformationRecipe,
                copiedRecipe
        );
        processorTask.put("copyableTransformationRecipe", copyableTransformationRecipe);
        processorTask.put("opencvTransformationRecipe", opencvTransformationRecipe);
        processorTask.put("previousShotContinuityContext", continuityContext);
        if (!copiedRecipe.isEmpty()) {
            processorTask.put("copiedFromRecipe", copiedRecipe.get("source"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "STUDIO_POLISH_CPU");
        payload.put("provider", "studio_polish_cpu");
        payload.put("aiCallsBudget", 1);
        payload.put("tokenPolicy", "one_image_call_per_shot_only");
        payload.put("scriptId", script.getId().toString());
        payload.put("shotNumber", take.shotNumber());
        payload.put("takeId", take.id().toString());
        payload.put("sourceAssetId", take.assetId().toString());
        payload.put("sourceContentType", take.contentType());
        payload.put("screenType", stringValue(script.getScreenType(), "vertical"));
        payload.put("storyboardTag", storyboardTag);
        payload.put("lightingBuildSheetTag", lightingTag);
        payload.put("cameraPlanSheetTag", cameraTag);
        payload.put("soundTimeline", soundTimeline);
        payload.put("previousShotContinuityContext", continuityContext);
        payload.put("frameSelection", frameSelection);
        payload.put("preset", preset);
        payload.put("studioPolishControls", controls);
        payload.put("lookRecipe", lookRecipe);
        payload.put("plateMode", plateMode);
        payload.put("platePrompt", platePrompt);
        payload.put("opencvPlan", opencvPlan);
        payload.put("opencvTransformationRecipe", opencvTransformationRecipe);
        payload.put("copyableTransformationRecipe", copyableTransformationRecipe);
        payload.put("copiedTransformationRecipe", !copiedRecipe.isEmpty());
        if (!copiedRecipe.isEmpty()) {
            payload.put("copiedFromRecipe", copiedRecipe.get("source"));
        }
        payload.put("processorTask", processorTask);
        payload.put("overrides", request == null || request.overrides() == null ? Map.of() : request.overrides());
        return payload;
    }

    private List<Map<String, Object>> buildFrameCandidates(int durationSeconds, List<Double> overrides) {
        List<Double> timestamps = new ArrayList<>();
        if (overrides != null && !overrides.isEmpty()) {
            overrides.stream()
                    .filter(value -> value != null && value >= 0)
                    .forEach(timestamps::add);
        } else {
            timestamps.add(0.35);
            timestamps.add(durationSeconds * 0.20);
            timestamps.add(durationSeconds * 0.50);
            timestamps.add(durationSeconds * 0.75);
            timestamps.add(Math.max(0.35, durationSeconds - 0.35));
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int index = 0; index < timestamps.size(); index++) {
            double timestamp = Math.max(0, Math.min(Math.max(0.1, durationSeconds - 0.1), timestamps.get(index)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", index + 1);
            item.put("timestampSeconds", round2(timestamp));
            item.put("purpose", switch (index) {
                case 0 -> "hook_frame";
                case 1 -> "early_action_frame";
                case 2 -> "representative_mid_frame";
                case 3 -> "late_action_frame";
                default -> "payoff_frame";
            });
            candidates.add(item);
        }
        return candidates;
    }

    private Map<String, Object> buildFrameScoringRules(String shotSize, Map<String, Object> storyboardTag, Map<String, Object> cameraTag) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("faceVisibleWeight", 30);
        rules.put("sharpnessWeight", 20);
        rules.put("exposureWeight", 15);
        rules.put("subjectCenterWeight", 15);
        rules.put("backgroundAreaWeight", 10);
        rules.put("shotPlanMatchWeight", 10);
        rules.put("targetFaceAreaRatio", switch (shotSize) {
            case "close_up" -> "0.22-0.45";
            case "wide" -> "0.02-0.10";
            default -> "0.08-0.22";
        });
        rules.put("minimumBackgroundAreaRatio", switch (shotSize) {
            case "close_up" -> 0.20;
            case "wide" -> 0.50;
            default -> 0.35;
        });
        rules.put("expectedFraming", firstText(cameraTag.get("framing"), cameraTag.get("shotSize"), cameraTag.get("cameraAngle"), storyboardTag.get("composition"), storyboardTag.get("visualComposition")));
        rules.put("expectedAction", firstText(storyboardTag.get("primaryAction"), storyboardTag.get("action"), storyboardTag.get("visualAction"), storyboardTag.get("shotAction")));
        rules.put("processorNote", "Do not call an LLM for frame scoring. Use face/pose landmarks, blur variance, histogram exposure, foreground area, and shot-size heuristics.");
        return rules;
    }

    private Map<String, Object> resolveCopiedStudioTransformationRecipe(ShotTakeData targetTake, ShotTakeStudioPolishRequest request) {
        Map<String, Object> directRecipe = objectMap(request == null ? null : request.transformationRecipe());
        if (!directRecipe.isEmpty()) {
            directRecipe.putIfAbsent("source", Map.of("type", "request_payload"));
            return directRecipe;
        }
        UUID sourceVariantId = request == null ? null : request.recipeSourceVariantId();
        if (sourceVariantId == null && request != null && request.overrides() != null) {
            sourceVariantId = uuidValue(request.overrides().get("recipeSourceVariantId"));
        }
        if (sourceVariantId == null) {
            return Map.of();
        }
        Map<String, Object> row = jdbcTemplate.query(
                """
                select
                    v.id as variant_id,
                    v.prompt_payload::text as prompt_payload,
                    st.id as source_take_id,
                    st.script_id as source_script_id,
                    st.shot_number as source_shot_number
                from creator_shot_enhancement_variants v
                join creator_shot_takes st on st.id = v.take_id
                where v.id = ?
                  and st.tenant_id = ?
                  and st.user_id = ?
                  and st.script_id = ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("variantId", rs.getObject("variant_id", UUID.class));
                    item.put("promptPayload", readMap(rs.getString("prompt_payload")));
                    item.put("sourceTakeId", rs.getObject("source_take_id", UUID.class));
                    item.put("sourceScriptId", rs.getObject("source_script_id", UUID.class));
                    item.put("sourceShotNumber", rs.getInt("source_shot_number"));
                    return item;
                },
                sourceVariantId,
                targetTake.tenantId(),
                targetTake.userId(),
                targetTake.scriptId()
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source Studio Polish recipe was not found for this project/script."));

        Map<String, Object> promptPayload = objectMap(row.get("promptPayload"));
        Map<String, Object> recipe = objectMap(promptPayload.get("copyableTransformationRecipe"));
        if (recipe.isEmpty()) {
            recipe = buildRecipeFromLegacyStudioPayload(promptPayload);
        }
        recipe.put("source", Map.of(
                "type", "variant",
                "variantId", String.valueOf(row.get("variantId")),
                "takeId", String.valueOf(row.get("sourceTakeId")),
                "scriptId", String.valueOf(row.get("sourceScriptId")),
                "shotNumber", row.get("sourceShotNumber")
        ));
        return recipe;
    }

    private Map<String, Object> buildRecipeFromLegacyStudioPayload(Map<String, Object> payload) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("version", "studio-polish-recipe-v1");
        copyIfPresent(payload, recipe, "preset");
        copyIfPresent(payload, recipe, "plateMode");
        copyIfPresent(payload, recipe, "studioPolishControls");
        copyIfPresent(payload, recipe, "lookRecipe");
        copyIfPresent(payload, recipe, "opencvPlan");
        copyIfPresent(payload, recipe, "opencvTransformationRecipe");
        recipe.put("source", Map.of("type", "legacy_prompt_payload"));
        return recipe;
    }

    private void applyCopiedStudioLookRecipe(
            Map<String, Object> targetLookRecipe,
            Map<String, Object> copiedRecipe,
            String targetShotSize,
            String editNote
    ) {
        Map<String, Object> copiedLook = objectMap(copiedRecipe.get("lookRecipe"));
        if (copiedLook.isEmpty()) {
            copiedLook = objectMap(copiedRecipe.get("transformationIntent"));
        }
        if (copiedLook.isEmpty()) {
            return;
        }
        deepMerge(targetLookRecipe, copiedLook);
        targetLookRecipe.put("shotSize", targetShotSize);
        targetLookRecipe.put("editNote", editNote);
        targetLookRecipe.put("copiedRecipeApplied", true);
        targetLookRecipe.put("copyBehavior", "preserve_source_background_lighting_grade_but_retarget_to_this_shot_frame_and_subject");
    }

    private void applyCopiedOpenCvPlan(
            Map<String, Object> opencvPlan,
            Map<String, Object> copiedRecipe,
            Map<String, Object> frameSelection,
            List<Map<String, Object>> soundTimeline,
            Map<String, Object> controls,
            Map<String, Object> lookRecipe
    ) {
        Map<String, Object> copiedPlan = objectMap(copiedRecipe.get("opencvPlan"));
        if (copiedPlan.isEmpty()) {
            copiedPlan = objectMap(copiedRecipe.get("opencvTransformationRecipe"));
        }
        if (!copiedPlan.isEmpty()) {
            deepMerge(opencvPlan, copiedPlan);
        }
        opencvPlan.put("frameSelection", frameSelection);
        opencvPlan.put("soundTimeline", soundTimeline);
        opencvPlan.put("controls", controls);
        opencvPlan.put("lookRecipe", lookRecipe);
        opencvPlan.put("copiedRecipeApplied", !copiedRecipe.isEmpty());
        opencvPlan.put("retargetingRule", "Never copy source video, source frame timestamp, source masks, or source camera motion. Copy only the look/transform parameters and recompute masks/motion for the target take.");
        List.of("takeId", "sourceTakeId", "sourceAssetId", "sourceVideoUrl", "sourceMediaUrl", "sourceFrameTimestampSeconds", "sourceMaskAssetId")
                .forEach(opencvPlan::remove);
    }

    private String resolveStudioPolishPreset(ShotTakeStudioPolishRequest request) {
        return resolveStudioPolishPreset(request, Map.of());
    }

    private String resolveStudioPolishPreset(ShotTakeStudioPolishRequest request, Map<String, Object> copiedRecipe) {
        String preset = firstText(
                request == null ? null : request.preset(),
                copiedRecipe.get("preset")
        );
        if (preset.isBlank() && request != null && request.overrides() != null) {
            preset = stringValue(request.overrides().get("preset"), "");
        }
        preset = preset.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (preset) {
            case "cinematic_warm", "premium_podcast", "dark_glossy", "bright_beauty", "minimal_creator" -> preset;
            default -> "clean_studio";
        };
    }

    private Map<String, Object> resolveStudioPolishControls(ShotTakeStudioPolishRequest request, String preset) {
        return resolveStudioPolishControls(request, preset, Map.of());
    }

    private Map<String, Object> resolveStudioPolishControls(ShotTakeStudioPolishRequest request, String preset, Map<String, Object> copiedRecipe) {
        Map<String, Object> controls = objectMap(firstNonNull(copiedRecipe.get("studioPolishControls"), copiedRecipe.get("controls")));
        if (controls.isEmpty()) {
            controls = defaultStudioPolishControls(preset);
        } else {
            controls = toStringObjectMap(controls);
        }
        if (request != null && request.studioPolishControls() != null) {
            deepMerge(controls, safeMap(request.studioPolishControls()));
        }
        if (request != null && request.overrides() != null) {
            Object legacyControls = request.overrides().get("studioPolishControls");
            if (legacyControls instanceof Map<?, ?> map) {
                deepMerge(controls, toStringObjectMap(map));
            }
        }
        clampStudioControls(controls);
        return controls;
    }

    private Map<String, Object> defaultStudioPolishControls(String preset) {
        Map<String, Object> controls = new LinkedHashMap<>();
        controls.put("background", section(
                "style", preset,
                "blur", 0.18,
                "darkness", 0.08,
                "warmth", 0.08,
                "saturation", 0.04,
                "replace", true,
                "regenerate", false
        ));
        controls.put("lighting", section(
                "faceBrightness", 0.16,
                "rimLight", 0.18,
                "contrast", 0.18,
                "shadows", -0.08,
                "highlights", -0.04,
                "glowBloom", 0.0,
                "vignette", 0.12
        ));
        controls.put("style", section(
                "cinematic", 0.45,
                "sharpness", 0.16,
                "skinSmoothing", 0.10,
                "colorWarmth", 0.08,
                "lutIntensity", 0.45,
                "grain", 0.03
        ));
        controls.put("camera", section(
                "zoom", 0.0,
                "crop", "auto",
                "stabilization", 0.25,
                "cinematicDrift", 0.0,
                "motionBlur", 0.0
        ));

        switch (defaultString(preset, "clean_studio")) {
            case "cinematic_warm" -> {
                deepMerge(controls, Map.of(
                        "background", section("warmth", 0.18, "darkness", 0.12, "saturation", 0.08),
                        "lighting", section("faceBrightness", 0.18, "rimLight", 0.24, "contrast", 0.24, "vignette", 0.18),
                        "style", section("cinematic", 0.68, "colorWarmth", 0.18, "lutIntensity", 0.62, "grain", 0.05)
                ));
            }
            case "premium_podcast" -> {
                deepMerge(controls, Map.of(
                        "background", section("style", "premium_podcast", "blur", 0.12, "darkness", 0.18, "warmth", 0.12),
                        "lighting", section("faceBrightness", 0.14, "rimLight", 0.30, "contrast", 0.22, "vignette", 0.20),
                        "style", section("cinematic", 0.55, "sharpness", 0.18, "lutIntensity", 0.50)
                ));
            }
            case "dark_glossy" -> {
                deepMerge(controls, Map.of(
                        "background", section("style", "dark_glossy", "darkness", 0.35, "saturation", -0.04),
                        "lighting", section("faceBrightness", 0.20, "rimLight", 0.38, "contrast", 0.32, "shadows", -0.20, "highlights", -0.08, "vignette", 0.28),
                        "style", section("cinematic", 0.72, "lutIntensity", 0.66, "grain", 0.06)
                ));
            }
            case "bright_beauty" -> {
                deepMerge(controls, Map.of(
                        "background", section("style", "bright_beauty", "blur", 0.22, "darkness", -0.08, "warmth", 0.12, "saturation", 0.08),
                        "lighting", section("faceBrightness", 0.28, "rimLight", 0.10, "contrast", 0.10, "shadows", 0.04, "highlights", -0.10, "vignette", 0.05),
                        "style", section("cinematic", 0.35, "skinSmoothing", 0.20, "colorWarmth", 0.10, "lutIntensity", 0.32, "grain", 0.0)
                ));
            }
            case "minimal_creator" -> {
                deepMerge(controls, Map.of(
                        "background", section("style", "minimal_creator", "blur", 0.08, "darkness", 0.02, "warmth", 0.02, "saturation", 0.0, "replace", false),
                        "lighting", section("faceBrightness", 0.10, "rimLight", 0.08, "contrast", 0.10, "shadows", -0.03, "highlights", -0.03, "vignette", 0.04),
                        "style", section("cinematic", 0.20, "sharpness", 0.10, "skinSmoothing", 0.06, "colorWarmth", 0.03, "lutIntensity", 0.18, "grain", 0.0)
                ));
            }
            default -> {
            }
        }
        return controls;
    }

    private Map<String, Object> section(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            map.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return map;
    }

    private Map<String, Object> buildStudioLookRecipe(
            Map<String, Object> storyboardTag,
            Map<String, Object> lightingTag,
            Map<String, Object> cameraTag,
            String shotSize,
            String editNote,
            String preset,
            Map<String, Object> controls
    ) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("style", "premium_creator_studio_polish");
        recipe.put("preset", preset);
        recipe.put("shotSize", shotSize);
        recipe.put("backgroundIntent", firstText(storyboardTag.get("productionDesign"), storyboardTag.get("backgroundDescription"), storyboardTag.get("setDesign"), storyboardTag.get("location"), "clean premium creator set matching the original camera perspective"));
        recipe.put("lightingIntent", firstText(lightingTag.get("keyLight"), lightingTag.get("lightingSetup"), lightingTag.get("mood"), storyboardTag.get("lighting"), "soft cinematic key light, subtle rim light, controlled shadows"));
        recipe.put("cameraIntent", firstText(cameraTag.get("cameraAngle"), cameraTag.get("shotSize"), cameraTag.get("lens"), cameraTag.get("movement"), "preserve original framing and perspective"));
        recipe.put("grade", Map.of(
                "contrast", "medium_high",
                "highlights", "warm_controlled",
                "shadows", "slight_teal_or_neutral",
                "skinTone", "natural_protected",
                "saturation", "moderate"
        ));
        recipe.put("selectiveEnhancement", Map.of(
                "faceExposureLift", "subtle",
                "faceSharpening", "subtle",
                "skinSmoothing", "very_subtle",
                "eyeClarity", "subtle",
                "edgeLightWrap", "enabled"
        ));
        recipe.put("editNote", editNote);
        recipe.put("controls", controls);
        return recipe;
    }

    private String buildStudioPlatePrompt(
            Map<String, Object> storyboardTag,
            Map<String, Object> lightingTag,
            Map<String, Object> cameraTag,
            Map<String, Object> lookRecipe,
            Map<String, Object> continuityContext,
            String plateMode,
            String editNote
    ) {
        return """
                Edit the provided reference frame for a low-cost CPU video compositing workflow.

                Output goal: %s.
                Create a clean cinematic background/set plate that matches the original camera perspective, focal length feel, aspect ratio, and subject position.
                Remove the creator/person completely from the output if generating a clean plate.
                Keep the set realistic and creator-video friendly, not fantasy. Do not add text, logos, watermarks, extra people, or foreground objects that would cover the creator.

                This image will be used once as an OpenCV background plate. The original creator video pixels will be segmented and composited back over it.
                Apply user edit note additively on top of production design; if empty, use production design and previous shot continuity only.
                Preserve actor face, expression, mouth/lip-sync, pose, framing, and hands when compositing the original video back.

                Look recipe: %s
                Storyboard tag: %s
                Lighting tag: %s
                Camera tag: %s
                Previous shot continuity context: %s
                User edit note to append: %s
                """.formatted(
                defaultString(plateMode, "clean_background_plate"),
                writeJson(lookRecipe),
                writeJson(storyboardTag),
                writeJson(lightingTag),
                writeJson(cameraTag),
                writeJson(continuityContext),
                defaultString(editNote, "EMPTY_USER_NOTE: enhance using production design plus continuity context only.")
        ).trim();
    }

    private Map<String, Object> buildOpenCvPlan(
            Map<String, Object> lookRecipe,
            Map<String, Object> frameSelection,
            List<Map<String, Object>> soundTimeline,
            Map<String, Object> controls
    ) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("runtime", "cpu_friendly_opencv_ffmpeg");
        plan.put("controls", controls);
        plan.put("controlMapping", buildOpenCvControlMapping());
        plan.put("doNotUse", List.of("per_frame_llm_calls", "local_heavy_gpu_generation"));
        plan.put("steps", List.of(
                "extract_candidate_frames",
                "score_best_frame_deterministically",
                "generate_or_load_clean_background_plate_once",
                "segment_creator_per_frame_with_cpu_portrait_matting",
                "estimate_camera_motion_with_feature_tracking",
                "warp_background_plate_to_camera_motion",
                "composite_creator_with_feathered_alpha_and_light_wrap",
                "apply_lut_and_skin_protected_grade",
                "apply_selective_face_enhancement",
                "mux_original_dialogue_audio",
                "optionally_mix_google_foley_and_ambience",
                "export_h264_aac_mp4"
        ));
        plan.put("frameSelection", frameSelection);
        plan.put("lookRecipe", lookRecipe);
        plan.put("soundTimeline", soundTimeline);
        plan.put("qualityGuardrails", List.of(
                "preserve_original_face_mouth_shape_and_dialogue_timing",
                "avoid_background_changes_that_need_strong_depth_parallax",
                "protect_hair_and_hands_edges_with_alpha_feathering",
                "prefer_subtle_realistic_enhancement_over_aggressive_scene_replacement"
        ));
        return plan;
    }

    private Map<String, Object> buildOpenCvTransformationRecipe(
            Map<String, Object> lookRecipe,
            Map<String, Object> controls,
            Map<String, Object> opencvPlan,
            Map<String, Object> copiedRecipe
    ) {
        Map<String, Object> background = sectionMap(controls, "background");
        Map<String, Object> lighting = sectionMap(controls, "lighting");
        Map<String, Object> style = sectionMap(controls, "style");
        Map<String, Object> camera = sectionMap(controls, "camera");

        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("version", "opencv-transformation-v1");
        recipe.put("runtime", "cpu_friendly_opencv_ffmpeg");
        recipe.put("copiedRecipeApplied", !copiedRecipe.isEmpty());
        recipe.put("lookRecipe", lookRecipe);
        recipe.put("backgroundTransforms", section(
                "strategy", Boolean.TRUE.equals(background.get("replace")) ? "clean_plate_composite" : "original_background_grade",
                "plateGeneration", "one_ai_reference_frame_to_clean_background_plate",
                "segmentation", "portrait_or_person_mask_per_frame_with_temporal_smoothing",
                "cameraMotion", "feature_tracking_homography_or_affine_warp_on_background_plate",
                "blur", background.get("blur"),
                "darkness", background.get("darkness"),
                "warmth", background.get("warmth"),
                "saturation", background.get("saturation")
        ));
        recipe.put("lightingTransforms", section(
                "faceBrightness", lighting.get("faceBrightness"),
                "rimLight", lighting.get("rimLight"),
                "contrast", lighting.get("contrast"),
                "shadows", lighting.get("shadows"),
                "highlights", lighting.get("highlights"),
                "glowBloom", lighting.get("glowBloom"),
                "vignette", lighting.get("vignette"),
                "implementation", "face_roi_exposure_lift_edge_light_wrap_tone_curve_vignette"
        ));
        recipe.put("styleTransforms", section(
                "cinematic", style.get("cinematic"),
                "sharpness", style.get("sharpness"),
                "skinSmoothing", style.get("skinSmoothing"),
                "colorWarmth", style.get("colorWarmth"),
                "lutIntensity", style.get("lutIntensity"),
                "grain", style.get("grain"),
                "implementation", "skin_protected_grade_unsharp_mask_bilateral_skin_smoothing_subtle_grain"
        ));
        recipe.put("cameraTransforms", section(
                "zoom", camera.get("zoom"),
                "crop", camera.get("crop"),
                "stabilization", camera.get("stabilization"),
                "cinematicDrift", camera.get("cinematicDrift"),
                "motionBlur", camera.get("motionBlur"),
                "implementation", "safe_crop_transform_smoothing_optional_drift"
        ));
        recipe.put("orderedOperations", opencvPlan.get("steps"));
        recipe.put("controlMapping", opencvPlan.get("controlMapping"));
        recipe.put("copyRules", List.of(
                "copy_background_lighting_grade_and_control_values",
                "recompute_target_frame_selection_masks_and_camera_motion",
                "do_not_copy_source_media_urls_or_source_frame_timestamps",
                "generate_or_load_a_new_plate_for_each_target_shot_if_background_replace_is_true"
        ));
        return recipe;
    }

    private Map<String, Object> buildCopyableStudioTransformationRecipe(
            ShotTakeData take,
            String preset,
            String plateMode,
            Map<String, Object> controls,
            Map<String, Object> lookRecipe,
            Map<String, Object> opencvPlan,
            Map<String, Object> opencvTransformationRecipe,
            Map<String, Object> copiedRecipe
    ) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("version", "studio-polish-recipe-v1");
        recipe.put("source", Map.of(
                "type", "take",
                "takeId", take.id().toString(),
                "scriptId", take.scriptId().toString(),
                "shotNumber", take.shotNumber()
        ));
        recipe.put("copiedRecipeApplied", !copiedRecipe.isEmpty());
        recipe.put("preset", preset);
        recipe.put("plateMode", plateMode);
        recipe.put("studioPolishControls", controls);
        recipe.put("lookRecipe", lookRecipe);
        recipe.put("opencvPlan", sanitizeCopyableOpenCvPlan(opencvPlan));
        recipe.put("opencvTransformationRecipe", opencvTransformationRecipe);
        recipe.put("whatGetsCopied", List.of(
                "set_design_background_intent",
                "lighting_intent",
                "color_grade",
                "studio_polish_control_values",
                "opencv_transformation_parameters"
        ));
        recipe.put("whatIsRecomputedPerShot", List.of(
                "best_reference_frame",
                "person_mask",
                "camera_motion",
                "background_plate_if_replace_is_enabled",
                "final_composite_and_audio_mux"
        ));
        return recipe;
    }

    private Map<String, Object> sanitizeCopyableOpenCvPlan(Map<String, Object> opencvPlan) {
        Map<String, Object> copy = new LinkedHashMap<>(opencvPlan);
        copy.remove("frameSelection");
        copy.remove("soundTimeline");
        copy.put("frameSelection", "recomputed_for_target_shot");
        copy.put("soundTimeline", "recomputed_for_target_shot");
        return copy;
    }

    private Map<String, Object> buildOpenCvControlMapping() {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("background.blur", "Gaussian/depth blur on generated or original background plate.");
        mapping.put("background.darkness", "Background exposure multiplier before composite.");
        mapping.put("background.warmth", "Background-only color temperature shift.");
        mapping.put("background.saturation", "Background-only saturation adjustment.");
        mapping.put("background.replace", "Use generated clean plate when true; otherwise preserve original background and grade it.");
        mapping.put("background.regenerate", "Worker may ignore cached/generated plate and request a fresh one, still capped to one image call per shot.");
        mapping.put("lighting.faceBrightness", "Face ROI exposure lift using face landmarks/mask.");
        mapping.put("lighting.rimLight", "Alpha-edge light wrap/rim boost around foreground subject.");
        mapping.put("lighting.contrast", "Global tone curve contrast.");
        mapping.put("lighting.shadows", "Shadow lift/crush control.");
        mapping.put("lighting.highlights", "Highlight recovery/rolloff control.");
        mapping.put("lighting.glowBloom", "Optional high-pass bloom; keep low to avoid cheap halos.");
        mapping.put("lighting.vignette", "Radial vignette applied after composite.");
        mapping.put("style.cinematic", "Overall blend for grade, contrast, vignette, and light-wrap intensity.");
        mapping.put("style.sharpness", "Unsharp mask amount with face-safe limits.");
        mapping.put("style.skinSmoothing", "Bilateral smoothing on skin/face ROI only.");
        mapping.put("style.colorWarmth", "Final image color temperature shift.");
        mapping.put("style.lutIntensity", "LUT blend amount.");
        mapping.put("style.grain", "Subtle grain/noise overlay.");
        mapping.put("camera.zoom", "Safe center/face-aware crop zoom.");
        mapping.put("camera.crop", "Output crop strategy: auto, original, vertical, horizontal, square.");
        mapping.put("camera.stabilization", "Frame transform smoothing strength.");
        mapping.put("camera.cinematicDrift", "Optional synthetic slow pan/scale drift. Default off.");
        mapping.put("camera.motionBlur", "Optional post-stabilization motion blur. Default off.");
        return mapping;
    }

    private Map<String, Object> buildStudioProcessorTask(
            CreatorScript script,
            ShotTakeData take,
            Map<String, Object> frameSelection,
            Map<String, Object> lookRecipe,
            Map<String, Object> opencvPlan,
            String platePrompt,
            Map<String, Object> controls,
            ShotTakeStudioPolishRequest request
    ) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskType", "STUDIO_POLISH_CPU");
        task.put("version", "studio-polish-v1");
        task.put("takeId", take.id().toString());
        task.put("scriptId", script.getId().toString());
        task.put("projectId", take.projectId() == null ? null : take.projectId().toString());
        task.put("shotNumber", take.shotNumber());
        task.put("sourceAssetId", take.assetId().toString());
        task.put("sourceVideoUrl", signedUrl(take.bucket(), take.objectKey(), ""));
        task.put("sourceContentType", take.contentType());
        task.put("outputObjectKeyPrefix", "shot-enhancements/%s/%02d/studio-polish/final".formatted(script.getId(), take.shotNumber()));
        task.put("frameSelection", frameSelection);
        task.put("lookRecipe", lookRecipe);
        task.put("controls", controls);
        task.put("platePrompt", platePrompt);
        task.put("opencvPlan", opencvPlan);
        task.put("googleImageCallPolicy", Map.of(
                "maxCallsPerShot", 1,
                "preferredInput", "best_scored_frame",
                "output", defaultString(request == null ? null : request.plateMode(), "clean_background_plate")
        ));
        task.put("completionCallback", "/api/v1/creator/storyboards/shots/takes/%s/studio-polish-complete".formatted(take.id()));
        return task;
    }

    private Map<String, Object> buildGoogleSoundDesignPrompt(
            CreatorScript script,
            CreatorScriptShotPlan plan,
            int shotNumber,
            List<Map<String, Object>> soundTimeline
    ) {
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("providerTarget", "google_audio_model");
        prompt.put("purpose", "Generate or mix foley, ambience, sync hits, and sound-bed layers for the polished take.");
        prompt.put("scriptId", script.getId().toString());
        prompt.put("shotNumber", shotNumber);
        prompt.put("shotTitle", firstText(storyboardTag.get("shotTitle"), storyboardTag.get("title"), "Shot " + shotNumber));
        prompt.put("timelineAllowsOverlap", true);
        prompt.put("layers", soundTimeline);
        prompt.put("audioMixStandards", defaultAudioMixStandards());
        prompt.put("instructions", List.of(
                "Keep original actor dialogue intelligible and aligned with the uploaded take.",
                "Keep dialogue at a consistent speech-first level.",
                "Duck background music under speech and keep ambience under the dialogue bed.",
                "Maintain ambient room tone that matches the scene.",
                "Use small whooshes, clicks, transitions, foley, and sync hits sparingly.",
                "Match reverb to the physical room or outdoor space.",
                "Use smooth fades between audio segments.",
                "Layer ambience, foley, and sync hits around dialogue; do not replace the actor voice unless requested.",
                "Return final audio cues with startSeconds, endSeconds, layerType, description, volumeLevel, and overlapsAllowed."
        ));
        return prompt;
    }

    private List<Map<String, Object>> buildSoundTimeline(CreatorScript script, CreatorScriptShotPlan plan, int shotNumber) {
        List<Map<String, Object>> layers = new ArrayList<>();
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        Map<String, Object> shot = script.getShots() == null
                ? Map.of()
                : script.getShots().stream()
                        .filter(item -> numberValue(item.get("shotNumber"), 0) == shotNumber)
                        .findFirst()
                        .orElse(Map.of());
        int durationSeconds = Math.max(1, numberValue(shot.get("durationSeconds"), Math.max(1, numberValue(script.getDurationSeconds(), 30) / Math.max(1, numberValue(script.getTotalShots(), 1)))));

        addTimelineLayer(layers, "ambient_bed", firstText(
                storyboardTag.get("ambientBedDescription"),
                storyboardTag.get("ambient_bed_description"),
                shot.get("ambientBedDescription"),
                shot.get("ambient_bed_description")
        ), 0.0, durationSeconds, "low", true);

        addTimelineLayer(layers, "sync_hit", firstText(
                storyboardTag.get("syncHitDescription"),
                storyboardTag.get("sync_hit_description"),
                shot.get("syncHitDescription"),
                shot.get("sync_hit_description")
        ), Math.max(0, durationSeconds - 0.5), durationSeconds, "high", true);

        appendSoundItems(layers, storyboardTag.get("soundDesign"), durationSeconds);
        appendSoundItems(layers, storyboardTag.get("soundCues"), durationSeconds);
        appendSoundItems(layers, storyboardTag.get("audioCues"), durationSeconds);
        appendSoundItems(layers, storyboardTag.get("foleyNotes"), durationSeconds);
        appendSoundItems(layers, shot.get("soundDesign"), durationSeconds);
        return layers;
    }

    private void appendSoundItems(List<Map<String, Object>> layers, Object value, int durationSeconds) {
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    rawMap.forEach((key, entryValue) -> map.put(String.valueOf(key), entryValue));
                    String description = firstText(map.get("description"), map.get("text"), map.get("cue"), map.get("note"));
                    String layerType = defaultString(String.valueOf(map.getOrDefault("layerType", map.getOrDefault("type", "foley"))), "foley");
                    double start = decimalValue(firstNonNull(map.get("startSeconds"), map.get("startTime"), map.get("timingSeconds")), 0.0);
                    double end = decimalValue(firstNonNull(map.get("endSeconds"), map.get("endTime")), Math.min(durationSeconds, start + 1.0));
                    addTimelineLayer(layers, layerType, description, start, end, firstText(map.get("volumeLevel"), map.get("volume")), true);
                } else {
                    addTimelineLayer(layers, "foley", String.valueOf(item), 0.0, durationSeconds, "medium", true);
                }
            }
        } else if (value instanceof String text && !text.isBlank()) {
            addTimelineLayer(layers, "foley", text, 0.0, durationSeconds, "medium", true);
        }
    }

    private void addTimelineLayer(List<Map<String, Object>> layers, String layerType, String description, double start, double end, String volume, boolean overlapsAllowed) {
        if (description == null || description.isBlank()) {
            return;
        }
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("layerType", defaultString(layerType, "foley"));
        layer.put("description", description);
        layer.put("startSeconds", Math.max(0, start));
        layer.put("endSeconds", Math.max(Math.max(0, start), end));
        layer.put("volumeLevel", defaultString(volume, "medium"));
        layer.put("overlapsAllowed", overlapsAllowed);
        layers.add(layer);
    }

    private void validateLyriaSoundGenerationRequest(ShotTakeSoundGenerateRequest request) {
        String layerType = normalizeSoundLayerType(request == null ? null : request.layerType());
        if (isDialogueLayerType(layerType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Lyria is only for non-dialogue music, ambience, foley, SFX, and sync hits. Use audio enhancement for dialogue cleanup."
            );
        }
        String prompt = defaultString(request == null ? null : request.prompt(), "");
        if (looksLikeDialogueEnhancementRequest(prompt)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This looks like dialogue/voice cleanup. Use enhance-audio-async so the original voice texture and words are preserved."
            );
        }
        if (looksLikeVoiceGenerationRequest(prompt)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Lyria generation is restricted to non-verbal clips here. Do not request vocals, lyrics, narration, voiceover, or dialogue."
            );
        }
    }

    private boolean isDialogueLayerType(String layerType) {
        String normalized = normalizeSoundLayerType(layerType);
        return "dialogue".equals(normalized) || "spoken".equals(normalized) || "voice".equals(normalized);
    }

    private boolean looksLikeDialogueEnhancementRequest(String prompt) {
        String text = normalizeIntentText(prompt);
        boolean mentionsVoice = containsAny(text, "dialogue", "voice", "speech", "spoken", "actor audio", "talking", "words");
        boolean asksCleanup = containsAny(text, "clean", "cleanup", "enhance", "denoise", "noise", "clarity", "clear", "de reverb", "dereverb", "de-ess", "hiss", "rumble");
        return mentionsVoice && asksCleanup;
    }

    private boolean looksLikeVoiceGenerationRequest(String prompt) {
        String text = normalizeIntentText(prompt);
        if (containsAny(text, "no vocal", "no vocals", "without vocal", "without vocals", "avoid vocal", "avoid vocals", "instrumental")) {
            return false;
        }
        return containsAny(
                text,
                "voiceover",
                "voice over",
                "narration",
                "spoken dialogue",
                "dialogue line",
                "lyrics",
                "singing",
                "singer",
                "rap verse",
                "vocal track",
                "with vocals",
                "add vocals",
                "generate vocals"
        );
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeIntentText(String value) {
        return defaultString(value, "").toLowerCase(Locale.ROOT).replace("-", " ").replace("_", " ");
    }

    private Map<String, Object> buildGeneratedSoundLayer(
            ShotTakeData take,
            ShotTakeSoundGenerateRequest request,
            String layerId,
            UUID jobId,
            String status
    ) {
        double start = Math.max(0.0, request == null || request.startSeconds() == null ? 0.0 : request.startSeconds());
        double duration = Math.max(0.25, request == null || request.durationSeconds() == null ? 4.0 : request.durationSeconds());
        double end = request != null && request.endSeconds() != null
                ? Math.max(start + 0.25, request.endSeconds())
                : start + duration;
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("id", defaultString(layerId, UUID.randomUUID().toString()));
        layer.put("source", "ai_generated");
        layer.put("status", defaultString(status, "GENERATION_PENDING"));
        layer.put("generationJobId", jobId == null ? null : jobId.toString());
        layer.put("layerType", normalizeSoundLayerType(request == null ? null : request.layerType()));
        layer.put("label", firstText(request == null ? null : request.mood(), request == null ? null : request.prompt(), "Generated sound"));
        layer.put("description", defaultString(request == null ? null : request.prompt(), "Generated sound"));
        layer.put("startSeconds", round2(start));
        layer.put("endSeconds", round2(end));
        layer.put("volumeDb", clampNumber(request == null || request.volumeDb() == null ? -12.0 : request.volumeDb(), -48.0, 6.0));
        layer.put("priority", 25);
        layer.put("duckUnder", "dialogue");
        layer.put("provider", resolveSoundGenerationProvider(request));
        layer.put("model", resolveSoundGenerationModel(request));
        layer.put("prompt", defaultString(request == null ? null : request.prompt(), ""));
        layer.put("mood", defaultString(request == null ? null : request.mood(), ""));
        layer.put("instrumentation", defaultString(request == null ? null : request.instrumentation(), ""));
        layer.put("bpmRange", defaultString(request == null ? null : request.bpmRange(), ""));
        layer.put("loopable", request != null && Boolean.TRUE.equals(request.loopable()));
        layer.put("avoidVocals", true);
        layer.put("nonDialogueOnly", true);
        layer.put("takeId", take.id().toString());
        layer.put("scriptId", take.scriptId().toString());
        layer.put("shotNumber", take.shotNumber());
        if (request != null && request.metadata() != null && !request.metadata().isEmpty()) {
            layer.put("metadata", toStringObjectMap(request.metadata()));
        }
        return normalizeSoundLayer(layer, 0);
    }

    private Map<String, Object> buildSoundGenerationProviderTask(
            CreatorScript script,
            CreatorScriptShotPlan plan,
            ShotTakeData take,
            ShotTakeSoundGenerateRequest request,
            UUID jobId,
            String layerId
    ) {
        Map<String, Object> storyboardTag = plan == null ? Map.of() : safeMap(plan.getStoryboardTag());
        Map<String, Object> editorSoundTimeline = loadEditorSoundTimeline(take.id());
        Map<String, Object> mixSettings = normalizeSoundMixSettings(editorSoundTimeline.get("mixSettings"), request == null ? null : request.mixSettings());
        String layerType = normalizeSoundLayerType(request == null ? null : request.layerType());
        double start = Math.max(0.0, request == null || request.startSeconds() == null ? 0.0 : request.startSeconds());
        double duration = Math.max(0.25, request == null || request.durationSeconds() == null ? 4.0 : request.durationSeconds());
        double end = request != null && request.endSeconds() != null
                ? Math.max(start + 0.25, request.endSeconds())
                : start + duration;
        String prompt = """
                Generate a production-ready audio clip for a creator video timeline.

                User requested sound: %s
                Layer type: %s
                Mood: %s
                Instrumentation: %s
                BPM/rhythm: %s
                Timeline slot: %.2fs to %.2fs

                Rules:
                - Create only the requested clip, not a full mix.
                - Keep output duration exactly %.2f seconds when possible.
                - Do not generate voice, vocals, lyrics, narration, or dialogue.
                - Use the audio-enhancement endpoint for recorded dialogue cleanup.
                - Make it loopable if requested.
                - Leave headroom for dialogue and obey ducking context.
                - Assume dialogue is primary and consistent; generated music/ambience/SFX must sit under speech.
                - Keep ambient room tone subtle and scene-matched.
                - Use small whooshes, clicks, and transitions sparingly.
                - Match reverb to the described scene space.
                - Make clip edges fade cleanly so timeline joins are smooth.
                - Do not include copyrighted melodies, recognizable songs, artist imitation, or trademarked samples.

                Shot context: %s
                Existing mix settings: %s
                Audio mix standards: %s
                """.formatted(
                defaultString(request == null ? null : request.prompt(), "Generated sound"),
                layerType,
                defaultString(request == null ? null : request.mood(), ""),
                defaultString(request == null ? null : request.instrumentation(), ""),
                defaultString(request == null ? null : request.bpmRange(), ""),
                start,
                end,
                end - start,
                firstText(storyboardTag.get("shotTitle"), storyboardTag.get("title"), "Shot " + take.shotNumber()),
                writeJson(mixSettings),
                writeJson(defaultAudioMixStandards())
        ).trim();
        String negativePrompt = "vocals, lyrics, singing, spoken dialogue, narration, voiceover, copyrighted melodies, famous songs, artist imitation, trademarked samples";
        String lyriaPrompt = """
                %s

                Role: %s.
                Mood: %s.
                Instrumentation: %s.
                Rhythm or tempo: %s.
                Shot context: %s.
                Audio mix standards: dialogue is primary, music ducks under speech, ambience stays as room tone, SFX are sparse, reverb matches the scene, and clip edges fade smoothly.
                Keep it clean, original, creator-video friendly, and easy to trim to %.2f seconds.
                """.formatted(
                defaultString(request == null ? null : request.prompt(), "Generate clean creator-video music."),
                layerType,
                defaultString(request == null ? null : request.mood(), "cinematic"),
                defaultString(request == null ? null : request.instrumentation(), "modern production"),
                defaultString(request == null ? null : request.bpmRange(), "natural tempo"),
                firstText(storyboardTag.get("shotTitle"), storyboardTag.get("title"), "Shot " + take.shotNumber()),
                end - start
        ).trim();

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskType", "SHOT_TAKE_SOUND_GENERATE");
        task.put("version", "sound-generate-v1");
        task.put("provider", resolveSoundGenerationProvider(request));
        task.put("model", resolveSoundGenerationModel(request));
        task.put("takeId", take.id().toString());
        task.put("scriptId", script.getId().toString());
        task.put("projectId", take.projectId() == null ? null : take.projectId().toString());
        task.put("shotNumber", take.shotNumber());
        task.put("generationJobId", jobId.toString());
        task.put("layerId", layerId);
        task.put("layerType", layerType);
        task.put("prompt", prompt);
        task.put("lyriaPrompt", lyriaPrompt);
        task.put("negativePrompt", negativePrompt);
        task.put("audioMixStandards", defaultAudioMixStandards());
        task.put("userPrompt", defaultString(request == null ? null : request.prompt(), ""));
        task.put("startSeconds", start);
        task.put("endSeconds", end);
        task.put("durationSeconds", end - start);
        task.put("outputContentType", String.valueOf(task.get("model")).startsWith("lyria-3") ? "audio/mpeg" : "audio/wav");
        task.put("outputObjectKeyPrefix", "shot-takes/%s/%02d/generated-sound".formatted(script.getId(), take.shotNumber()));
        task.put("completionCallback", "/api/v1/creator/storyboards/shots/takes/%s/sound-generate-complete".formatted(take.id()));
        task.put("callbackFields", Map.of("jobId", jobId.toString(), "layerId", layerId));
        task.put("mixSettings", mixSettings);
        task.put("guardrails", List.of(
                "no_copyrighted_melodies",
                "no_artist_imitation",
                "no_voice_vocals_lyrics_narration_or_dialogue",
                "generate_clip_only_not_full_mix",
                "dialogue_headroom_required"
        ));
        return task;
    }

    private void appendOrReplaceSoundTimelineLayer(UUID takeId, Map<String, Object> rawLayer) {
        Map<String, Object> analysis = loadTakeMediaAnalysis(takeId);
        Map<String, Object> timeline = normalizeSoundTimeline(null, null, analysis.get("soundTimeline"));
        List<Map<String, Object>> layers = soundLayerList(timeline.get("layers"));
        Map<String, Object> incomingLayer = rawLayer == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawLayer);
        String layerId = stringValue(incomingLayer.get("id"), "");
        List<Map<String, Object>> nextLayers = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> existing : layers) {
            if (!layerId.isBlank() && layerId.equals(stringValue(existing.get("id"), ""))) {
                Map<String, Object> merged = new LinkedHashMap<>(existing);
                merged.putAll(incomingLayer);
                nextLayers.add(normalizeSoundLayer(merged, nextLayers.size()));
                replaced = true;
            } else {
                nextLayers.add(existing);
            }
        }
        if (!replaced) {
            nextLayers.add(normalizeSoundLayer(incomingLayer, layers.size()));
        }
        timeline.put("layers", nextLayers);
        timeline.put("updatedAt", OffsetDateTime.now().toString());
        analysis.put("soundTimeline", timeline);
        analysis.put("audioMix", timeline.get("mixSettings"));
        analysis.put("updatedAt", OffsetDateTime.now().toString());
        saveTakeMediaAnalysis(takeId, analysis);
    }

    private void replaceSoundTimelineLayerAsset(UUID takeId, UUID sourceAssetId, CreatorAsset enhancedAsset, Map<String, Object> metadata) {
        Map<String, Object> analysis = loadTakeMediaAnalysis(takeId);
        Map<String, Object> timeline = normalizeSoundTimeline(null, null, analysis.get("soundTimeline"));
        List<Map<String, Object>> layers = soundLayerList(timeline.get("layers"));
        List<Map<String, Object>> nextLayers = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> existing : layers) {
            if (sourceAssetId.toString().equals(stringValue(existing.get("assetId"), ""))) {
                Map<String, Object> updated = new LinkedHashMap<>(existing);
                updated.put("previousAssetId", sourceAssetId.toString());
                updated.put("assetId", enhancedAsset.getId().toString());
                updated.put("assetUrl", signedUrl(enhancedAsset.getBucket(), enhancedAsset.getObjectKey(), enhancedAsset.getPublicUrl()));
                updated.put("contentType", enhancedAsset.getContentType());
                updated.put("sizeBytes", enhancedAsset.getSizeBytes());
                updated.put("source", "audio_enhanced");
                updated.put("status", "READY");
                updated.put("enhancedAt", OffsetDateTime.now().toString());
                updated.put("enhancementMemory", metadata == null ? Map.of() : metadata);
                nextLayers.add(normalizeSoundLayer(updated, nextLayers.size()));
                replaced = true;
            } else {
                nextLayers.add(existing);
            }
        }
        if (!replaced) {
            return;
        }
        timeline.put("layers", nextLayers);
        timeline.put("updatedAt", OffsetDateTime.now().toString());
        analysis.put("soundTimeline", timeline);
        analysis.put("audioMix", timeline.get("mixSettings"));
        analysis.put("updatedAt", OffsetDateTime.now().toString());
        saveTakeMediaAnalysis(takeId, analysis);
    }

    private String resolveSoundGenerationProvider(ShotTakeSoundGenerateRequest request) {
        return defaultString(request == null ? null : request.provider(), "google_lyria");
    }

    private String resolveSoundGenerationModel(ShotTakeSoundGenerateRequest request) {
        return defaultString(request == null ? null : request.model(), properties.getAi().getLyriaMusicModel());
    }

    private Map<String, Object> loadEditorSoundTimeline(UUID takeId) {
        Map<String, Object> analysis = loadTakeMediaAnalysis(takeId);
        return normalizeSoundTimeline(null, null, analysis.get("soundTimeline"));
    }

    private Map<String, Object> normalizeSoundTimeline(
            List<Map<String, Object>> requestLayers,
            Map<String, Object> requestMixSettings,
            Object existingRaw
    ) {
        Map<String, Object> existing = existingRaw instanceof Map<?, ?> existingMap
                ? toStringObjectMap(existingMap)
                : new LinkedHashMap<>();
        List<Map<String, Object>> layers = requestLayers == null
                ? soundLayerList(existing.get("layers"))
                : soundLayerList(requestLayers);
        Map<String, Object> mixSettings = normalizeSoundMixSettings(existing.get("mixSettings"), requestMixSettings);

        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("version", defaultString(stringValue(existing.get("version"), ""), "sound-timeline-v1"));
        timeline.put("layers", layers);
        timeline.put("mixSettings", mixSettings);
        timeline.put("updatedAt", OffsetDateTime.now().toString());
        return timeline;
    }

    private List<Map<String, Object>> soundLayerList(Object value) {
        List<Map<String, Object>> layers = new ArrayList<>();
        if (!(value instanceof List<?> rawLayers)) {
            return layers;
        }
        for (int index = 0; index < rawLayers.size(); index++) {
            Object item = rawLayers.get(index);
            if (item instanceof Map<?, ?> rawMap) {
                layers.add(normalizeSoundLayer(toStringObjectMap(rawMap), index));
            }
        }
        return layers;
    }

    private List<Map<String, Object>> textOverlayList(Object value) {
        List<Map<String, Object>> overlays = new ArrayList<>();
        if (!(value instanceof List<?> items)) {
            return overlays;
        }
        for (Object item : items) {
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> overlay = toStringObjectMap(rawMap);
                String text = firstText(overlay.get("text"), overlay.get("label"));
                if (text.isBlank()) {
                    continue;
                }
                overlay.put("text", text);
                overlays.add(overlay);
            }
        }
        return overlays;
    }

    private Map<String, Object> normalizeSoundLayer(Map<String, Object> raw, int index) {
        Map<String, Object> layer = new LinkedHashMap<>();
        double start = Math.max(0.0, decimalValue(firstNonNull(raw.get("startSeconds"), raw.get("startTime"), raw.get("timingSeconds")), 0.0));
        double end = Math.max(start, decimalValue(firstNonNull(raw.get("endSeconds"), raw.get("endTime")), start + 1.0));
        layer.put("id", defaultString(stringValue(raw.get("id"), ""), "sound-layer-" + index));
        layer.put("layerType", normalizeSoundLayerType(firstText(raw.get("layerType"), raw.get("type"))));
        layer.put("label", firstText(raw.get("label"), raw.get("description"), raw.get("cue"), raw.get("originalFilename"), "Layer " + (index + 1)));
        layer.put("description", firstText(raw.get("description"), raw.get("text"), raw.get("cue"), raw.get("note"), raw.get("label")));
        layer.put("startSeconds", round2(start));
        layer.put("endSeconds", round2(end));
        layer.put("volumeDb", clampNumber(decimalValue(firstNonNull(raw.get("volumeDb"), raw.get("gainDb")), -9.0), -48.0, 6.0));
        layer.put("priority", normalizeSoundPriority(firstText(raw.get("priority"), raw.get("duckingPriority")), index));
        layer.put("duckUnder", normalizeOvertakeLayer(firstText(raw.get("duckUnder"), raw.get("duckingTarget"), "dialogue")));
        layer.put("overtakesDialogue", Boolean.TRUE.equals(raw.get("overtakesDialogue")));
        layer.put("muted", Boolean.TRUE.equals(raw.get("muted")));
        copyIfPresent(raw, layer, "source");
        copyIfPresent(raw, layer, "assetId");
        copyIfPresent(raw, layer, "assetUrl");
        copyIfPresent(raw, layer, "contentType");
        copyIfPresent(raw, layer, "sizeBytes");
        copyIfPresent(raw, layer, "originalFilename");
        copyIfPresent(raw, layer, "status");
        copyIfPresent(raw, layer, "generationJobId");
        copyIfPresent(raw, layer, "provider");
        copyIfPresent(raw, layer, "model");
        copyIfPresent(raw, layer, "prompt");
        copyIfPresent(raw, layer, "mood");
        copyIfPresent(raw, layer, "instrumentation");
        copyIfPresent(raw, layer, "bpmRange");
        copyIfPresent(raw, layer, "loopable");
        copyIfPresent(raw, layer, "avoidVocals");
        copyIfPresent(raw, layer, "metadata");
        return layer;
    }

    private Map<String, Object> normalizeSoundMixSettings(Object baseRaw, Object overrideRaw) {
        Map<String, Object> mix = defaultSoundMixSettings();
        if (baseRaw instanceof Map<?, ?> baseMap) {
            deepMerge(mix, toStringObjectMap(baseMap));
        }
        if (overrideRaw instanceof Map<?, ?> overrideMap) {
            Map<String, Object> overrides = toStringObjectMap(overrideMap);
            Object nestedMixSettings = overrides.get("mixSettings");
            if (nestedMixSettings instanceof Map<?, ?> nestedMixMap) {
                deepMerge(mix, toStringObjectMap(nestedMixMap));
            } else {
                deepMerge(mix, overrides);
            }
        }
        mix.put("primaryLayer", normalizeOvertakeLayer(stringValue(mix.get("primaryLayer"), "dialogue")));
        mix.put("overtakeLayer", normalizeOvertakeLayer(stringValue(mix.get("overtakeLayer"), "dialogue")));
        mix.put("dialogueDuckingDb", clampNumber(decimalValue(mix.get("dialogueDuckingDb"), -10.0), -30.0, 0.0));
        mix.put("foleyDuckingDb", clampNumber(decimalValue(mix.get("foleyDuckingDb"), -6.0), -30.0, 0.0));
        mix.put("musicBedDb", clampNumber(decimalValue(mix.get("musicBedDb"), -18.0), -48.0, 0.0));
        mix.put("ambienceBedDb", clampNumber(decimalValue(mix.get("ambienceBedDb"), -22.0), -48.0, 0.0));
        mix.put("backgroundMusicDucksUnderDialogue", Boolean.parseBoolean(stringValue(mix.get("backgroundMusicDucksUnderDialogue"), "true")));
        mix.put("foleyDucksUnderDialogue", Boolean.parseBoolean(stringValue(mix.get("foleyDucksUnderDialogue"), "true")));
        mix.put("snippetFadeMs", clampNumber(decimalValue(mix.get("snippetFadeMs"), 120.0), 0.0, 2000.0));
        mix.put("audioMixStandards", audioMixStandards(mix.get("audioMixStandards"), mix.get("audio_mix_standards")));
        mix.put("volumeAutomation", volumeAutomationList(mix.get("volumeAutomation")));
        return mix;
    }

    private List<Map<String, Object>> volumeAutomationList(Object value) {
        List<Map<String, Object>> automation = new ArrayList<>();
        if (!(value instanceof List<?> rawItems)) {
            return automation;
        }
        for (int index = 0; index < rawItems.size(); index++) {
            Object item = rawItems.get(index);
            if (item instanceof Map<?, ?> rawMap) {
                automation.add(normalizeVolumeAutomation(toStringObjectMap(rawMap), index));
            }
        }
        return automation;
    }

    private Map<String, Object> normalizeVolumeAutomation(Map<String, Object> raw, int index) {
        double start = Math.max(0.0, decimalValue(firstNonNull(raw.get("startSeconds"), raw.get("startTime"), raw.get("start_seconds")), 0.0));
        double end = Math.max(start + 0.1, decimalValue(firstNonNull(raw.get("endSeconds"), raw.get("endTime"), raw.get("end_seconds")), start + 1.0));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", defaultString(stringValue(raw.get("id"), ""), "volume-automation-" + index));
        item.put("trackId", normalizeSoundLayerType(firstText(raw.get("trackId"), raw.get("track_id"), raw.get("layerType"), "dialogue")));
        item.put("startSeconds", round2(start));
        item.put("endSeconds", round2(end));
        item.put("volumeDb", clampNumber(decimalValue(firstNonNull(raw.get("volumeDb"), raw.get("volume_db"), raw.get("gainDb"), raw.get("gain_db")), 0.0), -48.0, 6.0));
        return item;
    }

    private Map<String, Object> defaultSoundMixSettings() {
        Map<String, Object> mix = new LinkedHashMap<>();
        mix.put("primaryLayer", "dialogue");
        mix.put("overtakeLayer", "dialogue");
        mix.put("dialogueDuckingDb", -10.0);
        mix.put("foleyDuckingDb", -6.0);
        mix.put("musicBedDb", -18.0);
        mix.put("ambienceBedDb", -22.0);
        mix.put("backgroundMusicDucksUnderDialogue", true);
        mix.put("foleyDucksUnderDialogue", true);
        mix.put("snippetFadeMs", 120.0);
        mix.put("audioMixStandards", defaultAudioMixStandards());
        return mix;
    }

    private Map<String, Object> audioMixStandards(Object... overrides) {
        Map<String, Object> standards = defaultAudioMixStandards();
        if (overrides != null) {
            for (Object override : overrides) {
                if (override instanceof Map<?, ?> raw) {
                    standards.putAll(toStringObjectMap(raw));
                }
            }
        }
        return standards;
    }

    private Map<String, Object> defaultAudioMixStandards() {
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
        return standards;
    }

    private List<Map<String, Object>> soundSnippetAssets(List<Map<String, Object>> layers) {
        List<Map<String, Object>> assets = new ArrayList<>();
        for (Map<String, Object> layer : layers) {
            String assetId = stringValue(layer.get("assetId"), "");
            if (assetId.isBlank()) {
                continue;
            }
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("assetId", assetId);
            asset.put("assetUrl", stringValue(layer.get("assetUrl"), ""));
            asset.put("layerId", stringValue(layer.get("id"), ""));
            asset.put("layerType", stringValue(layer.get("layerType"), ""));
            asset.put("contentType", stringValue(layer.get("contentType"), ""));
            assets.add(asset);
        }
        return assets;
    }

    private String normalizeSoundLayerType(String value) {
        String normalized = defaultString(value, "foley").toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (List.of("dialogue", "spoken", "voice", "foley", "ambience", "ambient_bed", "music", "background_music", "sfx", "sync_hit").contains(normalized)) {
            return normalized;
        }
        return "foley";
    }

    private String normalizeOvertakeLayer(String value) {
        String normalized = defaultString(value, "dialogue").toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (normalized.equals("spoken") || normalized.equals("voice")) {
            return "dialogue";
        }
        if (normalized.equals("bgm")) {
            return "background_music";
        }
        if (List.of("dialogue", "foley", "background_music", "music", "ambience", "sfx").contains(normalized)) {
            return normalized;
        }
        return "dialogue";
    }

    private int normalizeSoundPriority(String value, int fallback) {
        int priority = numberValue(value, Math.max(1, fallback + 1));
        return Math.max(1, Math.min(99, priority));
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private ReferenceImage resolveReferenceImage(ShotTakeData take) {
        if (take == null) {
            return null;
        }
        if (take.referenceFrameAssetId() != null
                && isImageContentType(take.referenceFrameContentType())
                && !defaultString(take.referenceFrameBucket(), "").isBlank()
                && !defaultString(take.referenceFrameObjectKey(), "").isBlank()) {
            return new ReferenceImage(
                    take.referenceFrameAssetId(),
                    take.referenceFrameContentType(),
                    take.referenceFrameBucket(),
                    take.referenceFrameObjectKey(),
                    "reference_frame"
            );
        }
        if (isImageContentType(take.contentType())
                && !defaultString(take.bucket(), "").isBlank()
                && !defaultString(take.objectKey(), "").isBlank()) {
            return new ReferenceImage(
                    take.assetId(),
                    take.contentType(),
                    take.bucket(),
                    take.objectKey(),
                    "uploaded_image_take"
            );
        }
        return null;
    }

    private ReferenceImage resolveVeoSourceImage(ShotTakeData take, UUID approvedVariantId) {
        ReferenceImage approvedPreview = loadPreviewImageSource(take.id(), approvedVariantId, "approved_polished_preview");
        if (approvedPreview != null) {
            return approvedPreview;
        }
        ReferenceImage latestPreview = loadPreviewImageSource(take.id(), null, "latest_polished_preview");
        if (latestPreview != null) {
            return latestPreview;
        }
        return resolveReferenceImage(take);
    }

    private boolean addContinuityReferenceInput(
            List<StoryboardImageGenerationService.ReferenceImageInput> referenceInputs,
            ContinuityReferenceImage continuityReference
    ) {
        if (continuityReference == null || continuityReference.referenceImage() == null) {
            return false;
        }
        ReferenceImage image = continuityReference.referenceImage();
        if (!isImageContentType(image.contentType())) {
            return false;
        }
        try {
            byte[] bytes = assetStorageService.s3Client()
                    .getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(image.bucket())
                            .key(image.objectKey())
                            .build())
                    .asByteArray();
            if (bytes == null || bytes.length == 0) {
                return false;
            }
            referenceInputs.add(new StoryboardImageGenerationService.ReferenceImageInput(
                    bytes,
                    image.contentType(),
                    image.role()
            ));
            return true;
        } catch (RuntimeException ex) {
            log.warn(
                    "Could not load previous continuity reference image variantId={} assetId={} reason={}",
                    continuityReference.variantId(),
                    image.assetId(),
                    shortText(defaultString(ex.getMessage(), ex.getClass().getSimpleName()), 400)
            );
            return false;
        }
    }

    private ContinuityReferenceImage resolveContinuityReferenceImage(UUID scriptId, ShotTakeData take, UUID currentVariantId) {
        if (scriptId == null || take == null) {
            return null;
        }
        String excludeCurrentVariantClause = currentVariantId == null ? "" : " and v.id <> ? ";
        List<Object> params = new ArrayList<>();
        params.add(scriptId);
        params.add(take.tenantId());
        params.add(take.userId());
        if (currentVariantId != null) {
            params.add(currentVariantId);
        }
        params.add(take.id());
        params.add(take.shotNumber());
        params.add(take.id());
        params.add(take.id());
        return jdbcTemplate.query(
                """
                select v.id as variant_id,
                       v.take_id,
                       t.shot_number,
                       v.preview_asset_id,
                       v.status,
                       v.provider,
                       v.prompt_payload::text as prompt_payload_json,
                       a.content_type,
                       a.bucket,
                       a.object_key
                from creator_shot_enhancement_variants v
                join creator_shot_takes t on t.id = v.take_id
                join creator_assets a on a.id = v.preview_asset_id
                where t.script_id = ?
                  and t.tenant_id = ?
                  and t.user_id = ?
                  and v.preview_asset_id is not null
                """ + excludeCurrentVariantClause + """
                  and (t.id = ? or t.shot_number < ?)
                  and v.status in ('TIMELINE_APPLIED', 'PREVIEW_READY', 'VIDEO_READY')
                order by
                  case
                    when t.id = ? and v.status = 'TIMELINE_APPLIED' then 0
                    when t.id = ? then 1
                    when v.status = 'TIMELINE_APPLIED' then 2
                    else 3
                  end,
                  t.shot_number desc,
                  v.updated_at desc,
                  v.created_at desc
                limit 1
                """,
                (rs, rowNum) -> new ContinuityReferenceImage(
                        rs.getObject("variant_id", UUID.class),
                        rs.getObject("take_id", UUID.class),
                        rs.getInt("shot_number"),
                        rs.getString("status"),
                        rs.getString("provider"),
                        readMap(rs.getString("prompt_payload_json")),
                        new ReferenceImage(
                                rs.getObject("preview_asset_id", UUID.class),
                                rs.getString("content_type"),
                                rs.getString("bucket"),
                                rs.getString("object_key"),
                                "previous_variant_continuity_reference"
                        )
                ),
                params.toArray()
        ).stream().findFirst().orElse(null);
    }

    private ReferenceImage loadPreviewImageSource(UUID takeId, UUID variantId, String role) {
        String variantClause = variantId == null ? "" : " and v.id = ? ";
        List<ReferenceImage> images = jdbcTemplate.query(
                """
                select a.id, a.content_type, a.bucket, a.object_key
                from creator_shot_enhancement_variants v
                join creator_assets a on a.id = v.preview_asset_id
                where v.take_id = ?
                  and v.preview_asset_id is not null
                """ + variantClause + """
                order by v.updated_at desc, v.created_at desc
                limit 1
                """,
                (rs, rowNum) -> new ReferenceImage(
                        rs.getObject("id", UUID.class),
                        rs.getString("content_type"),
                        rs.getString("bucket"),
                        rs.getString("object_key"),
                        role
                ),
                variantId == null ? new Object[]{takeId} : new Object[]{takeId, variantId}
        );
        return images.stream()
                .filter(image -> isImageContentType(image.contentType()))
                .findFirst()
                .orElse(null);
    }

    private UUID createVeoVariant(UUID takeId, UUID jobId, Map<String, Object> promptPayload) {
        UUID variantId = UUID.randomUUID();
        String provider = studioPolishVideoGenerationService.normalizeProvider(stringValue(promptPayload.get("provider"), ""));
        jdbcTemplate.update(
                """
                insert into creator_shot_enhancement_variants (
                    id, take_id, generation_job_id, status, provider, prompt_payload, created_at, updated_at
                ) values (?, ?, ?, 'VIDEO_PENDING', ?, cast(? as jsonb), now(), now())
                """,
                variantId,
                takeId,
                jobId,
                provider,
                writeJson(promptPayload)
        );
        return variantId;
    }

    private void markVeoVariantPromptReady(UUID variantId, Map<String, Object> promptPayload) {
        String provider = studioPolishVideoGenerationService.normalizeProvider(stringValue(promptPayload.get("provider"), ""));
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'VIDEO_PROMPT_READY',
                    provider = ?,
                    prompt_payload = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                provider,
                writeJson(promptPayload),
                variantId
        );
    }

    private void markVeoVariantReferenceRequired(UUID variantId, Map<String, Object> promptPayload, String message) {
        String provider = studioPolishVideoGenerationService.normalizeProvider(stringValue(promptPayload.get("provider"), ""));
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'REFERENCE_FRAME_REQUIRED',
                    provider = ?,
                    prompt_payload = cast(? as jsonb),
                    provider_response = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                provider,
                writeJson(promptPayload),
                writeJson(Map.of(
                        "status", "REFERENCE_FRAME_REQUIRED",
                        "message", defaultString(message, "Reference frame required before selected Studio Polish provider generation.")
                )),
                variantId
        );
    }

    private StudioPolishProviderQueueService.ProviderQueueLease acquireStudioPolishProviderSlot(
            String provider,
            String providerLabel,
            UUID jobId,
            UUID variantId,
            ShotTakeData take,
            Map<String, Object> studioPayload
    ) {
        return studioPolishProviderQueueService.acquire(provider, jobId, variantId, take.shotNumber(), status -> {
            Map<String, Object> queueMetadata = status.metadata();
            studioPayload.put("providerQueue", queueMetadata);
            markVeoVariantProviderQueued(variantId, studioPayload, queueMetadata);
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    32,
                    "Queued for " + providerLabel + " provider slot for shot " + take.shotNumber(),
                    Map.of(
                            "takeId", take.id().toString(),
                            "variantId", variantId.toString(),
                            "studioPolish", studioPayload,
                            "providerQueue", queueMetadata
                    )
            );
        });
    }

    private void markVeoVariantProviderQueued(UUID variantId, Map<String, Object> promptPayload, Map<String, Object> queueMetadata) {
        String provider = studioPolishVideoGenerationService.normalizeProvider(stringValue(promptPayload.get("provider"), ""));
        Map<String, Object> providerResponse = new LinkedHashMap<>();
        providerResponse.put("status", "VIDEO_PROVIDER_QUEUED");
        providerResponse.put("message", "Waiting for provider queue slot.");
        providerResponse.put("providerQueue", queueMetadata == null ? Map.of() : queueMetadata);
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'VIDEO_PROVIDER_QUEUED',
                    provider = ?,
                    prompt_payload = cast(? as jsonb),
                    provider_response = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                provider,
                writeJson(promptPayload),
                writeJson(providerResponse),
                variantId
        );
    }

    private void markVeoVariantRunning(UUID variantId, Map<String, Object> promptPayload) {
        String provider = studioPolishVideoGenerationService.normalizeProvider(stringValue(promptPayload.get("provider"), ""));
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'VIDEO_RUNNING',
                    provider = ?,
                    updated_at = now()
                where id = ?
                """,
                provider,
                variantId
        );
    }

    private void markVeoVariantReady(UUID variantId, UUID finalVideoAssetId, StudioPolishVideoGenerationService.GeneratedVideo generatedVideo) {
        String provider = studioPolishVideoGenerationService.normalizeProvider(stringValue(generatedVideo.metadata().get("provider"), ""));
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'VIDEO_READY',
                    final_video_asset_id = ?,
                    provider = ?,
                    provider_operation_id = ?,
                    provider_request = cast(? as jsonb),
                    provider_response = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                finalVideoAssetId,
                provider,
                generatedVideo.operationName(),
                writeJson(generatedVideo.providerRequest()),
                writeJson(generatedVideo.providerResponse()),
                variantId
        );
    }

    private void markVeoVariantFailed(UUID variantId, String message) {
        Map<String, Object> providerResponse = new LinkedHashMap<>();
        providerResponse.put("message", defaultString(message, "Studio Polish video generation failed."));
        jdbcTemplate.update(
                """
                update creator_shot_enhancement_variants
                set status = 'FAILED',
                    provider_response = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                writeJson(providerResponse),
                variantId
        );
    }

    private void ensureVariantBelongsToTake(UUID variantId, UUID takeId) {
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "variantId is required.");
        }
        Boolean exists = jdbcTemplate.query(
                """
                select exists(
                    select 1
                    from creator_shot_enhancement_variants
                    where id = ?
                      and take_id = ?
                )
                """,
                (rs, rowNum) -> rs.getBoolean(1),
                variantId,
                takeId
        ).stream().findFirst().orElse(false);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Studio Polish variant was not found for this take.");
        }
    }

    private void ensureGenerationJobBelongsToTake(UUID jobId, UUID takeId) {
        if (jobId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is required.");
        }
        Map<String, Object> input = takeJobInput(jobId);
        UUID inputTakeId = uuidValue(input.get("takeId"));
        if (!takeId.equals(inputTakeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sound generation job was not found for this take.");
        }
    }

    private int shotDurationSeconds(CreatorScript script, int shotNumber) {
        Map<String, Object> shot = script.getShots() == null
                ? Map.of()
                : script.getShots().stream()
                        .filter(item -> numberValue(item.get("shotNumber"), 0) == shotNumber)
                        .findFirst()
                        .orElse(Map.of());
        return Math.max(
                1,
                numberValue(
                        shot.get("durationSeconds"),
                        Math.max(1, numberValue(script.getDurationSeconds(), 30) / Math.max(1, numberValue(script.getTotalShots(), 1)))
                )
        );
    }

    private String inferShotSize(Map<String, Object> storyboardTag, Map<String, Object> cameraTag) {
        String text = firstText(
                cameraTag.get("shotSize"),
                cameraTag.get("framing"),
                cameraTag.get("cameraAngle"),
                storyboardTag.get("shotSize"),
                storyboardTag.get("composition"),
                storyboardTag.get("visualComposition")
        ).toLowerCase(Locale.ROOT);
        if (text.contains("close") || text.contains("cu") || text.contains("face")) {
            return "close_up";
        }
        if (text.contains("wide") || text.contains("full body") || text.contains("establish")) {
            return "wide";
        }
        return "medium";
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private CreatorScript loadScript(UUID scriptId, String tenantId, String userId) {
        return scriptRepository.findByIdAndTenantIdAndUserId(scriptId, safeTenantId(tenantId), safeUserId(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Script was not found."));
    }

    private CreatorScriptShotPlan loadShotPlan(UUID scriptId, int shotNumber) {
        return shotPlanRepository.findByScriptIdAndShotNumberAndStyleKey(scriptId, shotNumber, DEFAULT_STYLE_KEY)
                .orElseGet(() -> shotPlanRepository.findByScriptIdOrderByShotNumberAsc(scriptId).stream()
                        .filter(plan -> numberValue(plan.getShotNumber(), 0) == shotNumber)
                        .findFirst()
                        .orElse(null));
    }

    private ShotTakeData loadTake(UUID takeId, String tenantId, String userId) {
        return jdbcTemplate.query(
                        takeDataSql() + """
                                where t.id = ?
                                  and t.tenant_id = ?
                                  and t.user_id = ?
                                """,
                        (rs, rowNum) -> new ShotTakeData(
                                rs.getObject("id", UUID.class),
                                rs.getString("tenant_id"),
                                rs.getString("user_id"),
                                rs.getObject("project_id", UUID.class),
                                rs.getObject("script_id", UUID.class),
                                rs.getInt("shot_number"),
                                rs.getObject("asset_id", UUID.class),
                                rs.getString("status"),
                                rs.getString("review_status"),
                                rs.getBoolean("accepted"),
                                rs.getString("user_notes"),
                                rs.getString("content_type"),
                                rs.getObject("size_bytes", Long.class),
                                rs.getString("bucket"),
                                rs.getString("object_key"),
                                rs.getObject("reference_frame_asset_id", UUID.class),
                                rs.getString("reference_frame_content_type"),
                                rs.getString("reference_frame_bucket"),
                                rs.getString("reference_frame_object_key")
                        ),
                        takeId,
                        safeTenantId(tenantId),
                        safeUserId(userId)
                )
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shot take was not found."));
    }

    private List<ShotTakeData> loadAcceptedTakes(UUID scriptId, String tenantId, String userId) {
        return jdbcTemplate.query(
                takeDataSql() + """
                        where t.script_id = ?
                          and t.tenant_id = ?
                          and t.user_id = ?
                          and t.accepted = true
                        order by t.shot_number asc, t.updated_at desc
                        """,
                (rs, rowNum) -> new ShotTakeData(
                        rs.getObject("id", UUID.class),
                        rs.getString("tenant_id"),
                        rs.getString("user_id"),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("script_id", UUID.class),
                        rs.getInt("shot_number"),
                        rs.getObject("asset_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("review_status"),
                        rs.getBoolean("accepted"),
                        rs.getString("user_notes"),
                        rs.getString("content_type"),
                        rs.getObject("size_bytes", Long.class),
                        rs.getString("bucket"),
                        rs.getString("object_key"),
                        rs.getObject("reference_frame_asset_id", UUID.class),
                        rs.getString("reference_frame_content_type"),
                        rs.getString("reference_frame_bucket"),
                        rs.getString("reference_frame_object_key")
                ),
                scriptId,
                safeTenantId(tenantId),
                safeUserId(userId)
        );
    }

    private LocalShotSequenceRenderService.SequenceClip sequenceClipForAcceptedTake(ShotTakeData take) {
        VariantMediaData media = null;
        try {
            media = loadVariantMediaData(take.id(), null, false);
        } catch (ResponseStatusException ignored) {
            // An accepted take can still be previewed from the uploaded original until polish/final render exists.
        }
        if (media != null && media.finalRenderAssetId() != null) {
            return new LocalShotSequenceRenderService.SequenceClip(
                    take.id(),
                    take.shotNumber(),
                    media.finalRenderAssetId(),
                    media.finalRenderBucket(),
                    media.finalRenderObjectKey(),
                    media.finalRenderContentType(),
                    "final_render"
            );
        }
        if (media != null && media.finalVideoAssetId() != null) {
            return new LocalShotSequenceRenderService.SequenceClip(
                    take.id(),
                    take.shotNumber(),
                    media.finalVideoAssetId(),
                    media.finalVideoBucket(),
                    media.finalVideoObjectKey(),
                    media.finalVideoContentType(),
                    "polished_video"
            );
        }
        if (isVideoContentType(take.contentType())) {
            return new LocalShotSequenceRenderService.SequenceClip(
                    take.id(),
                    take.shotNumber(),
                    take.assetId(),
                    take.bucket(),
                    take.objectKey(),
                    take.contentType(),
                    "original_take"
            );
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accepted shot " + take.shotNumber() + " needs a video take before final sequence preview.");
    }

    private Map<String, Object> latestAcceptedSequencePreview(UUID scriptId, List<ShotTakeData> acceptedTakes, String tenantId, String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                select id,
                       asset_type,
                       bucket,
                       object_key,
                       content_type,
                       size_bytes,
                       public_url,
                       metadata::text as metadata_json,
                       created_at
                from creator_assets
                where tenant_id = ?
                  and user_id = ?
                  and metadata ->> 'scriptId' = ?
                  and metadata ->> 'assetKind' = 'accepted_shot_sequence_preview'
                order by created_at desc
                limit 1
                """,
                safeTenantId(tenantId),
                safeUserId(userId),
                scriptId.toString()
        );
        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("scriptId", scriptId.toString());
            empty.put("status", acceptedTakes.isEmpty() ? "EMPTY" : "PENDING_RENDER");
            empty.put("assetId", null);
            empty.put("url", "");
            empty.put("acceptedTakeCount", acceptedTakes.size());
            empty.put("shotNumbers", acceptedTakes.stream().map(ShotTakeData::shotNumber).toList());
            empty.put("takeIds", acceptedTakes.stream().map(take -> take.id().toString()).toList());
            empty.put("message", acceptedTakes.isEmpty()
                    ? "Accept a shot take to build the final preview."
                    : "Accepted shots are ready to render into the mobile preview.");
            return empty;
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> metadata = readMap(stringValue(row.get("metadata_json"), "{}"));
        CreatorAsset asset = CreatorAsset.builder()
                .id(uuidValue(row.get("id")))
                .assetType(stringValue(row.get("asset_type"), ASSET_TYPE_SHOT_TAKE_FINAL_SEQUENCE_VIDEO))
                .bucket(stringValue(row.get("bucket"), ""))
                .objectKey(stringValue(row.get("object_key"), ""))
                .contentType(stringValue(row.get("content_type"), "video/mp4"))
                .sizeBytes(longValue(row.get("size_bytes")))
                .publicUrl(stringValue(row.get("public_url"), ""))
                .metadata(metadata)
                .build();
        return acceptedSequencePreviewFromAsset(asset, metadata, acceptedTakes);
    }

    private Map<String, Object> acceptedSequencePreviewFromAsset(CreatorAsset asset, Map<String, Object> metadata, List<ShotTakeData> acceptedTakes) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scriptId", stringValue(metadata == null ? null : metadata.get("scriptId"), ""));
        response.put("status", "FINAL_SEQUENCE_READY");
        response.put("assetId", asset.getId() == null ? null : asset.getId().toString());
        response.put("assetType", asset.getAssetType());
        response.put("url", signedUrl(asset.getBucket(), asset.getObjectKey(), asset.getPublicUrl()));
        response.put("contentType", asset.getContentType());
        response.put("sizeBytes", asset.getSizeBytes());
        response.put("bucket", asset.getBucket());
        response.put("objectKey", asset.getObjectKey());
        response.put("acceptedTakeCount", acceptedTakes == null ? numberValue(metadata == null ? null : metadata.get("acceptedTakeCount"), 0) : acceptedTakes.size());
        response.put("shotNumbers", acceptedTakes == null ? objectList(metadata == null ? null : metadata.get("shotNumbers")) : acceptedTakes.stream().map(ShotTakeData::shotNumber).toList());
        response.put("takeIds", acceptedTakes == null ? objectList(metadata == null ? null : metadata.get("takeIds")) : acceptedTakes.stream().map(take -> take.id().toString()).toList());
        response.put("metadata", metadata == null ? Map.of() : metadata);
        response.put("message", "Accepted-shot mobile preview is ready.");
        return response;
    }

    private ShotTakeResponse toTakeResponse(UUID takeId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                takeSelectSql() + " where t.id = ?",
                takeId
        );
        UUID assetId = uuidValue(row.get("asset_id"));
        String assetUrl = signedUrl(stringValue(row.get("bucket"), ""), stringValue(row.get("object_key"), ""), stringValue(row.get("public_url"), ""));
        UUID referenceFrameAssetId = uuidValue(row.get("reference_frame_asset_id"));
        String referenceFrameUrl = signedUrl(
                stringValue(row.get("reference_frame_bucket"), ""),
                stringValue(row.get("reference_frame_object_key"), ""),
                stringValue(row.get("reference_frame_public_url"), "")
        );
        return new ShotTakeResponse(
                uuidValue(row.get("id")),
                uuidValue(row.get("project_id")),
                uuidValue(row.get("script_id")),
                numberValue(row.get("shot_number"), 0),
                assetId,
                assetUrl,
                stringValue(row.get("content_type"), ""),
                longValue(row.get("size_bytes")),
                referenceFrameAssetId,
                referenceFrameUrl,
                stringValue(row.get("reference_frame_content_type"), ""),
                stringValue(row.get("status"), ""),
                stringValue(row.get("review_status"), ""),
                Boolean.TRUE.equals(row.get("accepted")),
                stringValue(row.get("user_notes"), ""),
                readMap(stringValue(row.get("media_analysis"), "{}")),
                readMap(stringValue(row.get("validation_summary"), "{}")),
                loadReviews(takeId),
                loadVariants(takeId),
                offsetDateTime(row.get("created_at")),
                offsetDateTime(row.get("updated_at"))
        );
    }

    private List<ShotTakeReviewResponse> loadReviews(UUID takeId) {
        return jdbcTemplate.query(
                """
                select id, take_id, generation_job_id, status, score, checks::text as checks,
                       sound_timeline::text as sound_timeline, message, created_at
                from creator_shot_take_reviews
                where take_id = ?
                order by created_at desc
                """,
                (rs, rowNum) -> new ShotTakeReviewResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("take_id", UUID.class),
                        rs.getObject("generation_job_id", UUID.class),
                        rs.getString("status"),
                        rs.getBigDecimal("score") == null ? null : rs.getBigDecimal("score").doubleValue(),
                        readMap(rs.getString("checks")),
                        readList(rs.getString("sound_timeline")),
                        rs.getString("message"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                takeId
        );
    }

    private List<ShotTakeEnhancementVariantResponse> loadVariants(UUID takeId) {
        return jdbcTemplate.query(
                """
                select v.id, v.take_id, v.generation_job_id, v.preview_asset_id, v.status,
                       v.final_video_asset_id, v.final_audio_asset_id, v.final_render_asset_id, v.provider, v.provider_operation_id,
                       v.prompt_payload::text as prompt_payload, v.provider_response::text as provider_response,
                       v.user_feedback, v.created_at, v.updated_at,
                       a.bucket as preview_bucket, a.object_key as preview_object_key, a.public_url as preview_public_url,
                       va.bucket as final_video_bucket, va.object_key as final_video_object_key, va.public_url as final_video_public_url,
                       aa.bucket as final_audio_bucket, aa.object_key as final_audio_object_key, aa.public_url as final_audio_public_url,
                       fra.bucket as final_render_bucket, fra.object_key as final_render_object_key, fra.public_url as final_render_public_url
                from creator_shot_enhancement_variants v
                left join creator_assets a on a.id = v.preview_asset_id
                left join creator_assets va on va.id = v.final_video_asset_id
                left join creator_assets aa on aa.id = v.final_audio_asset_id
                left join creator_assets fra on fra.id = v.final_render_asset_id
                where v.take_id = ?
                order by v.created_at desc
                """,
                (rs, rowNum) -> new ShotTakeEnhancementVariantResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("take_id", UUID.class),
                        rs.getObject("generation_job_id", UUID.class),
                        rs.getObject("preview_asset_id", UUID.class),
                        signedUrl(rs.getString("preview_bucket"), rs.getString("preview_object_key"), rs.getString("preview_public_url")),
                        rs.getObject("final_video_asset_id", UUID.class),
                        signedUrl(rs.getString("final_video_bucket"), rs.getString("final_video_object_key"), rs.getString("final_video_public_url")),
                        rs.getObject("final_audio_asset_id", UUID.class),
                        signedUrl(rs.getString("final_audio_bucket"), rs.getString("final_audio_object_key"), rs.getString("final_audio_public_url")),
                        rs.getObject("final_render_asset_id", UUID.class),
                        signedUrl(rs.getString("final_render_bucket"), rs.getString("final_render_object_key"), rs.getString("final_render_public_url")),
                        rs.getString("status"),
                        rs.getString("provider"),
                        rs.getString("provider_operation_id"),
                        readMap(rs.getString("prompt_payload")),
                        readMap(rs.getString("provider_response")),
                        rs.getString("user_feedback"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                takeId
        );
    }

    private VariantMediaData loadVariantMediaData(UUID takeId, UUID variantId, boolean requireFinalVideo) {
        List<VariantMediaData> rows;
        if (variantId != null) {
            rows = jdbcTemplate.query(
                    variantMediaSql() + """
                            where v.take_id = ?
                              and v.id = ?
                            limit 1
                            """,
                    (rs, rowNum) -> variantMediaData(rs),
                    takeId,
                    variantId
            );
        } else {
            rows = jdbcTemplate.query(
                    variantMediaSql() + """
                            where v.take_id = ?
                            order by
                              case when v.final_render_asset_id is not null then 0 else 1 end,
                              case when v.final_video_asset_id is not null then 0 else 1 end,
                              v.updated_at desc
                            limit 1
                            """,
                    (rs, rowNum) -> variantMediaData(rs),
                    takeId
            );
        }
        VariantMediaData media = rows.stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No polish variant found for this take."));
        if (requireFinalVideo && media.finalVideoAssetId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Render/polish the shot video before final rendering or frame extraction.");
        }
        return media;
    }

    private VariantMediaData loadLatestFinalAudioMediaData(UUID takeId) {
        return jdbcTemplate.query(
                variantMediaSql() + """
                        where v.take_id = ?
                          and v.final_audio_asset_id is not null
                        order by v.updated_at desc
                        limit 1
                        """,
                (rs, rowNum) -> variantMediaData(rs),
                takeId
        ).stream().findFirst().orElse(new VariantMediaData(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    private VariantMediaData loadLatestFinalVideoMediaData(UUID takeId) {
        return jdbcTemplate.query(
                variantMediaSql() + """
                        where v.take_id = ?
                          and v.final_video_asset_id is not null
                        order by v.created_at desc
                        limit 1
                        """,
                (rs, rowNum) -> variantMediaData(rs),
                takeId
        ).stream().findFirst().orElse(null);
    }

    private String variantMediaSql() {
        return """
                select v.id as variant_id,
                       v.final_video_asset_id,
                       v.final_audio_asset_id,
                       v.final_render_asset_id,
                       va.bucket as final_video_bucket,
                       va.object_key as final_video_object_key,
                       va.content_type as final_video_content_type,
                       aa.bucket as final_audio_bucket,
                       aa.object_key as final_audio_object_key,
                       aa.content_type as final_audio_content_type,
                       fra.bucket as final_render_bucket,
                       fra.object_key as final_render_object_key,
                       fra.content_type as final_render_content_type
                from creator_shot_enhancement_variants v
                left join creator_assets va on va.id = v.final_video_asset_id
                left join creator_assets aa on aa.id = v.final_audio_asset_id
                left join creator_assets fra on fra.id = v.final_render_asset_id
                """;
    }

    private VariantMediaData variantMediaData(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VariantMediaData(
                rs.getObject("variant_id", UUID.class),
                rs.getObject("final_video_asset_id", UUID.class),
                rs.getString("final_video_bucket"),
                rs.getString("final_video_object_key"),
                rs.getString("final_video_content_type"),
                rs.getObject("final_audio_asset_id", UUID.class),
                rs.getString("final_audio_bucket"),
                rs.getString("final_audio_object_key"),
                rs.getString("final_audio_content_type"),
                rs.getObject("final_render_asset_id", UUID.class),
                rs.getString("final_render_bucket"),
                rs.getString("final_render_object_key"),
                rs.getString("final_render_content_type")
        );
    }

    private UUID latestVariantIdForTake(UUID takeId) {
        return jdbcTemplate.query(
                """
                select id
                from creator_shot_enhancement_variants
                where take_id = ?
                order by created_at desc
                limit 1
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                takeId
        ).stream().findFirst().orElse(null);
    }

    private UUID latestVariantIdForJob(UUID jobId) {
        return jdbcTemplate.query(
                """
                select id
                from creator_shot_enhancement_variants
                where generation_job_id = ?
                order by created_at desc
                limit 1
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                jobId
        ).stream().findFirst().orElse(null);
    }

    private UUID generationJobIdForVariant(UUID variantId) {
        return jdbcTemplate.query(
                """
                select generation_job_id
                from creator_shot_enhancement_variants
                where id = ?
                """,
                (rs, rowNum) -> rs.getObject("generation_job_id", UUID.class),
                variantId
        ).stream().findFirst().orElse(null);
    }

    private Map<String, Object> takeJobInput(UUID jobId) {
        return jdbcTemplate.query(
                "select input_payload::text from creator_generation_jobs where id = ?",
                (rs, rowNum) -> readMap(rs.getString(1)),
                jobId
        ).stream().findFirst().orElse(Map.of());
    }

    private Map<String, Object> generationJobOutput(UUID jobId) {
        return jdbcTemplate.query(
                "select output_payload::text from creator_generation_jobs where id = ?",
                (rs, rowNum) -> readMap(rs.getString(1)),
                jobId
        ).stream().findFirst().orElse(Map.of());
    }

    private Map<String, Object> loadTakeMediaAnalysis(UUID takeId) {
        return jdbcTemplate.query(
                "select media_analysis::text from creator_shot_takes where id = ?",
                (rs, rowNum) -> readMap(rs.getString(1)),
                takeId
        ).stream().findFirst().orElse(new LinkedHashMap<>());
    }

    private void saveTakeMediaAnalysis(UUID takeId, Map<String, Object> analysis) {
        jdbcTemplate.update(
                """
                update creator_shot_takes
                set media_analysis = cast(? as jsonb),
                    updated_at = now()
                where id = ?
                """,
                writeJson(analysis == null ? Map.of() : analysis),
                takeId
        );
    }

    private void resetPolishedVideoAnalysis(UUID takeId, UUID variantId, UUID finalVideoAssetId) {
        Map<String, Object> analysis = loadTakeMediaAnalysis(takeId);
        Map<String, Object> polishedVideo = new LinkedHashMap<>();
        polishedVideo.put("source", "backend_ffmpeg_pending");
        polishedVideo.put("frameExtractionStatus", "pending");
        polishedVideo.put("frames", List.of());
        polishedVideo.put("sourceVariantId", variantId == null ? null : variantId.toString());
        polishedVideo.put("sourceVideoAssetId", finalVideoAssetId == null ? null : finalVideoAssetId.toString());
        polishedVideo.put("updatedAt", OffsetDateTime.now().toString());
        polishedVideo.put("message", "New polished video saved. Extract enhanced frames for this variant before showing polished timeline thumbnails.");
        analysis.put("polishedVideo", polishedVideo);
        analysis.put("updatedAt", OffsetDateTime.now().toString());
        saveTakeMediaAnalysis(takeId, analysis);
    }

    private String takeSelectSql() {
        return """
                select t.id, t.tenant_id, t.user_id, t.project_id, t.script_id, t.shot_number,
                       t.asset_id, t.reference_frame_asset_id, t.status, t.review_status, t.accepted, t.user_notes,
                       t.media_analysis::text as media_analysis,
                       t.validation_summary::text as validation_summary, t.created_at, t.updated_at,
                       a.content_type, a.size_bytes, a.bucket, a.object_key, a.public_url,
                       rf.content_type as reference_frame_content_type,
                       rf.bucket as reference_frame_bucket,
                       rf.object_key as reference_frame_object_key,
                       rf.public_url as reference_frame_public_url
                from creator_shot_takes t
                join creator_assets a on a.id = t.asset_id
                left join creator_assets rf on rf.id = t.reference_frame_asset_id
                """;
    }

    private String takeDataSql() {
        return """
                select t.id, t.tenant_id, t.user_id, t.project_id, t.script_id, t.shot_number,
                       t.asset_id, t.reference_frame_asset_id, t.status, t.review_status, t.accepted, t.user_notes,
                       a.content_type, a.size_bytes, a.bucket, a.object_key,
                       rf.content_type as reference_frame_content_type,
                       rf.bucket as reference_frame_bucket,
                       rf.object_key as reference_frame_object_key
                from creator_shot_takes t
                join creator_assets a on a.id = t.asset_id
                left join creator_assets rf on rf.id = t.reference_frame_asset_id
                """;
    }

    private String signedUrl(String bucket, String objectKey, String fallbackUrl) {
        if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()) {
            return defaultString(fallbackUrl, "");
        }
        try {
            return assetStorageService.signedUrl(bucket, objectKey, SIGNED_URL_TTL);
        } catch (RuntimeException ex) {
            return defaultString(fallbackUrl, "");
        }
    }

    private boolean isImageContentType(String contentType) {
        return stringValue(contentType, "").toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private boolean isVideoContentType(String contentType) {
        return stringValue(contentType, "").toLowerCase(Locale.ROOT).startsWith("video/");
    }

    private boolean isAudioContentType(String contentType) {
        return stringValue(contentType, "").toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    private String audioFileExtension(String contentType) {
        String normalized = stringValue(contentType, "audio/wav").toLowerCase(Locale.ROOT);
        if (normalized.contains("mpeg") || normalized.contains("mp3")) return "mp3";
        if (normalized.contains("ogg")) return "ogg";
        if (normalized.contains("flac")) return "flac";
        if (normalized.contains("aac")) return "aac";
        return "wav";
    }

    private String sanitizeFilename(String filename) {
        String name = Paths.get(defaultString(filename, "shot-upload")).getFileName().toString();
        return name.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String safeTenantId(String tenantId) {
        return defaultString(tenantId, "unknown");
    }

    private String safeUserId(String userId) {
        return defaultString(userId, "anonymous");
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringObjectMap(map);
        }
        return new LinkedHashMap<>();
    }

    private CreatorGenerationJob findActiveStudioPolishJob(
            String tenantId,
            String userId,
            String jobType,
            String idempotencyKey
    ) {
        return generationJobService
                .findActiveGenerationJobByIdempotencyKey(tenantId, userId, jobType, idempotencyKey)
                .orElse(null);
    }

    private String studioPolishIdempotencyKey(
            String jobType,
            UUID subjectId,
            String provider,
            String model,
            String providerMode,
            Long providerSeed,
            String preset,
            Map<String, Object> studioPolishControls,
            ShotTakeStudioPolishRequest request
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("version", 1);
        canonical.put("jobType", jobType);
        canonical.put("subjectId", subjectId == null ? null : subjectId.toString());
        canonical.put("provider", provider);
        canonical.put("model", model);
        canonical.put("providerMode", providerMode);
        canonical.put("seed", providerSeed);
        canonical.put("preset", preset);
        canonical.put("editNote", defaultString(request == null ? null : request.editNote(), ""));
        canonical.put("generatePlateFromReference", request == null ? null : request.generatePlateFromReference());
        canonical.put("plateMode", request == null ? null : request.plateMode());
        canonical.put("candidateTimestampsSeconds", request == null ? null : request.candidateTimestampsSeconds());
        canonical.put("recipeSourceVariantId", request == null || request.recipeSourceVariantId() == null ? null : request.recipeSourceVariantId().toString());
        canonical.put("transformationRecipe", request == null ? Map.of() : safeMap(request.transformationRecipe()));
        canonical.put("studioPolishControls", studioPolishControls == null ? Map.of() : safeMap(studioPolishControls));
        canonical.put("overrides", request == null ? Map.of() : safeMap(request.overrides()));
        try {
            String canonicalJson = objectMapper
                    .writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(canonical);
            return "studio-polish:" + sha256Hex(canonicalJson);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create Studio Polish idempotency key.", ex);
        }
    }

    private Long resolveStudioPolishProviderSeed(String provider, UUID subjectId, ShotTakeStudioPolishRequest request) {
        String normalizedProvider = studioPolishVideoGenerationService.normalizeProvider(provider);
        if (!"runway".equalsIgnoreCase(normalizedProvider) && !"decart".equalsIgnoreCase(normalizedProvider)) {
            return null;
        }
        Object requested = firstNonNull(
                request == null ? null : request.seed(),
                request == null || request.overrides() == null ? null : request.overrides().get("seed"),
                request == null || request.overrides() == null ? null : request.overrides().get("runwaySeed"),
                request == null || request.overrides() == null ? null : request.overrides().get("decartSeed"),
                request == null || request.overrides() == null ? null : request.overrides().get("providerSeed")
        );
        Long parsed = parseRunwaySeed(requested);
        if (parsed != null) {
            return parsed;
        }
        return unsignedSeedFrom("studio-polish-%s:%s".formatted(normalizedProvider, subjectId == null ? "unknown" : subjectId));
    }

    private Long parseRunwaySeed(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        long seed;
        if (value instanceof Number number) {
            seed = number.longValue();
        } else {
            try {
                seed = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider seed must be an integer from 0 to 4294967295.");
            }
        }
        if (seed < 0 || seed > 4_294_967_295L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider seed must be between 0 and 4294967295.");
        }
        return seed;
    }

    private long unsignedSeedFrom(String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(defaultString(value, "").getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
        return ((long) digest[0] & 0xff) << 24
                | ((long) digest[1] & 0xff) << 16
                | ((long) digest[2] & 0xff) << 8
                | ((long) digest[3] & 0xff);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(defaultString(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (rawMap == null) {
            return map;
        }
        rawMap.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                map.put(String.valueOf(key), toStringObjectMap(nested));
            } else {
                map.put(String.valueOf(key), value);
            }
        });
        return map;
    }

    private void publishProviderUsageFromMetadata(
            String promptType,
            Map<String, Object> providerMetadata,
            ShotTakeData take,
            UUID jobId,
            String description
    ) {
        Map<String, Object> costMetadata = objectMap(providerMetadata == null ? null : providerMetadata.get("costMetadata"));
        if (costMetadata.isEmpty()) {
            return;
        }
        String provider = firstText(
                costMetadata.get("provider"),
                providerMetadata == null ? null : providerMetadata.get("provider"),
                promptType
        );
        String model = firstText(
                costMetadata.get("model"),
                providerMetadata == null ? null : providerMetadata.get("model"),
                ""
        );
        creatorAiService.publishProviderUsageDebit(
                promptType,
                provider,
                model,
                costMetadata,
                new CreatorAiService.AiUsageContext(
                        take.tenantId(),
                        take.userId(),
                        take.projectId(),
                        jobId,
                        null
                ),
                description
        );
    }

    private Map<String, Object> aggregateProviderCostMetadata(String promptType, List<Map<String, Object>> costMetadataItems) {
        if (costMetadataItems == null || costMetadataItems.isEmpty()) {
            return new LinkedHashMap<>();
        }
        double totalCost = 0.0;
        double billableTotalCost = 0.0;
        String currency = "";
        String provider = "";
        String model = "";
        for (Map<String, Object> item : costMetadataItems) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            totalCost += decimalValue(item.get("totalCost"), 0.0);
            billableTotalCost += decimalValue(firstNonNull(
                    item.get("billableTotalCost"),
                    item.get("customerTotalCost"),
                    item.get("totalCost")
            ), 0.0);
            if (currency.isBlank()) {
                currency = firstText(item.get("currency"));
            }
            if (provider.isBlank()) {
                provider = firstText(item.get("provider"));
            }
            if (model.isBlank()) {
                model = firstText(item.get("model"));
            }
        }
        if (totalCost <= 0.0) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("promptType", promptType);
        aggregate.put("provider", provider);
        aggregate.put("model", model);
        aggregate.put("currency", currency);
        aggregate.put("rateUnit", "AGGREGATE_PROVIDER_USAGE");
        aggregate.put("totalCost", BigDecimal.valueOf(totalCost));
        aggregate.put("actualTotalCost", BigDecimal.valueOf(totalCost));
        aggregate.put("billableTotalCost", BigDecimal.valueOf(billableTotalCost > 0.0 ? billableTotalCost : totalCost));
        aggregate.put("customerTotalCost", BigDecimal.valueOf(billableTotalCost > 0.0 ? billableTotalCost : totalCost));
        aggregate.put("items", costMetadataItems);
        return aggregate;
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        if (target == null || source == null) {
            return;
        }
        source.forEach((key, value) -> {
            Object existing = target.get(key);
            if (existing instanceof Map<?, ?> existingMap && value instanceof Map<?, ?> valueMap) {
                Map<String, Object> merged = existingMap instanceof LinkedHashMap<?, ?>
                        ? (Map<String, Object>) existingMap
                        : toStringObjectMap(existingMap);
                target.put(key, merged);
                deepMerge(merged, toStringObjectMap(valueMap));
            } else {
                target.put(key, value);
            }
        });
    }

    private void clampStudioControls(Map<String, Object> controls) {
        Map<String, Object> background = sectionMap(controls, "background");
        clamp(background, "blur", 0.0, 1.0);
        clamp(background, "darkness", -0.5, 0.8);
        clamp(background, "warmth", -0.5, 0.5);
        clamp(background, "saturation", -0.6, 0.8);
        normalizeBoolean(background, "replace", true);
        normalizeBoolean(background, "regenerate", false);

        Map<String, Object> lighting = sectionMap(controls, "lighting");
        clamp(lighting, "faceBrightness", -0.3, 0.6);
        clamp(lighting, "rimLight", 0.0, 0.8);
        clamp(lighting, "contrast", -0.4, 0.7);
        clamp(lighting, "shadows", -0.6, 0.6);
        clamp(lighting, "highlights", -0.6, 0.4);
        clamp(lighting, "glowBloom", 0.0, 0.4);
        clamp(lighting, "vignette", 0.0, 0.6);

        Map<String, Object> style = sectionMap(controls, "style");
        clamp(style, "cinematic", 0.0, 1.0);
        clamp(style, "sharpness", 0.0, 0.8);
        clamp(style, "skinSmoothing", 0.0, 0.5);
        clamp(style, "colorWarmth", -0.5, 0.5);
        clamp(style, "lutIntensity", 0.0, 1.0);
        clamp(style, "grain", 0.0, 0.25);

        Map<String, Object> camera = sectionMap(controls, "camera");
        clamp(camera, "zoom", 0.0, 0.25);
        clamp(camera, "stabilization", 0.0, 1.0);
        clamp(camera, "cinematicDrift", 0.0, 0.3);
        clamp(camera, "motionBlur", 0.0, 0.3);
        String crop = stringValue(camera.get("crop"), "auto").toLowerCase(Locale.ROOT);
        if (!List.of("auto", "original", "vertical", "horizontal", "square").contains(crop)) {
            crop = "auto";
        }
        camera.put("crop", crop);
    }

    private Map<String, Object> sectionMap(Map<String, Object> controls, String key) {
        Object value = controls.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = toStringObjectMap(map);
            controls.put(key, normalized);
            return normalized;
        }
        Map<String, Object> section = new LinkedHashMap<>();
        controls.put(key, section);
        return section;
    }

    private void clamp(Map<String, Object> section, String key, double min, double max) {
        section.put(key, Math.max(min, Math.min(max, decimalValue(section.get(key), 0.0))));
    }

    private double clampValue(Double value, double min, double max, double fallback) {
        double next = value == null ? fallback : value;
        return Math.max(min, Math.min(max, next));
    }

    private double clampNumber(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void normalizeBoolean(Map<String, Object> section, String key, boolean fallback) {
        Object value = section.get(key);
        if (value instanceof Boolean bool) {
            section.put(key, bool);
            return;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            section.put(key, fallback);
            return;
        }
        section.put(key, Boolean.parseBoolean(String.valueOf(value)));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(defaultString(json, "{}"), new TypeReference<>() {});
        } catch (IOException ex) {
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, Object>> readList(String json) {
        try {
            return objectMapper.readValue(defaultString(json, "[]"), new TypeReference<>() {});
        } catch (IOException ex) {
            return new ArrayList<>();
        }
    }

    private List<Object> objectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not serialize shot take payload.", ex);
        }
    }

    private CreatorAsset upsertAsset(CreatorAsset asset) {
        UUID assetId = jdbcTemplate.queryForObject(
                """
                insert into creator_assets (
                    id,
                    tenant_id,
                    user_id,
                    project_id,
                    storyboard_id,
                    asset_type,
                    bucket,
                    object_key,
                    content_type,
                    size_bytes,
                    public_url,
                    metadata,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), now())
                on conflict (bucket, object_key) do update set
                    tenant_id = excluded.tenant_id,
                    user_id = excluded.user_id,
                    project_id = excluded.project_id,
                    storyboard_id = excluded.storyboard_id,
                    asset_type = excluded.asset_type,
                    content_type = excluded.content_type,
                    size_bytes = excluded.size_bytes,
                    public_url = excluded.public_url,
                    metadata = excluded.metadata
                returning id
                """,
                UUID.class,
                UUID.randomUUID(),
                asset.getTenantId(),
                asset.getUserId(),
                asset.getProjectId(),
                asset.getStoryboardId(),
                asset.getAssetType(),
                asset.getBucket(),
                asset.getObjectKey(),
                asset.getContentType(),
                asset.getSizeBytes(),
                asset.getPublicUrl(),
                writeJson(asset.getMetadata() == null ? Map.of() : asset.getMetadata())
        );
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("Saved creator asset was not found: " + assetId));
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int numberValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null || String.valueOf(value).isBlank() ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double decimalValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null || String.valueOf(value).isBlank() ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double firstPositiveDecimal(Object... values) {
        for (Object value : values) {
            double next = decimalValue(value, 0.0);
            if (next > 0) {
                return next;
            }
        }
        return 0.0;
    }

    private Double divideMillis(Object value) {
        double millis = decimalValue(value, 0.0);
        return millis > 0 ? millis / 1000.0 : null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null || String.valueOf(value).isBlank() ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private OffsetDateTime offsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        return null;
    }

    private static class GeneratedMultipartFile implements MultipartFile {
        private final String filename;
        private final String contentType;
        private final byte[] bytes;

        private GeneratedMultipartFile(String filename, String contentType, byte[] bytes) {
            this.filename = filename;
            this.contentType = contentType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }

        @Override
        public String getName() {
            return filename;
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }
    private record ShotTakeData(
            UUID id,
            String tenantId,
            String userId,
            UUID projectId,
            UUID scriptId,
            int shotNumber,
            UUID assetId,
            String status,
            String reviewStatus,
            boolean accepted,
            String userNotes,
            String contentType,
            Long sizeBytes,
            String bucket,
            String objectKey,
            UUID referenceFrameAssetId,
            String referenceFrameContentType,
            String referenceFrameBucket,
            String referenceFrameObjectKey
    ) {
    }

    private record ReferenceImage(
            UUID assetId,
            String contentType,
            String bucket,
            String objectKey,
            String role
    ) {
    }

    private record AudioEnhancementSource(
            UUID assetId,
            String assetType,
            String role,
            String contentType,
            Long sizeBytes,
            String bucket,
            String objectKey,
            Map<String, Object> metadata
    ) {
    }

    private record VariantMediaData(
            UUID variantId,
            UUID finalVideoAssetId,
            String finalVideoBucket,
            String finalVideoObjectKey,
            String finalVideoContentType,
            UUID finalAudioAssetId,
            String finalAudioBucket,
            String finalAudioObjectKey,
            String finalAudioContentType,
            UUID finalRenderAssetId,
            String finalRenderBucket,
            String finalRenderObjectKey,
            String finalRenderContentType
    ) {
    }

    private record SourceVideoData(
            UUID assetId,
            String bucket,
            String objectKey,
            String contentType,
            String role
    ) {
    }

    private record ContinuityReferenceImage(
            UUID variantId,
            UUID takeId,
            int shotNumber,
            String status,
            String provider,
            Map<String, Object> promptPayload,
            ReferenceImage referenceImage
    ) {
    }

    private record ProviderPromptCompilation(
            String prompt,
            Map<String, Object> metadata
    ) {
    }
}





