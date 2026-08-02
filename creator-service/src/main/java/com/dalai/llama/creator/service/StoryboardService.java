package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.domain.entity.CreatorStoryboard;
import com.dalai.llama.creator.domain.entity.CreatorStoryboardScene;
import com.dalai.llama.creator.dto.request.GenerateStoryboardRequest;
import com.dalai.llama.creator.dto.request.ShotAiEditRequest;
import com.dalai.llama.creator.dto.request.ShotTimelineInsertRequest;
import com.dalai.llama.creator.dto.response.ShotImageUrlResponse;
import com.dalai.llama.creator.dto.response.StoryboardResponse;
import com.dalai.llama.creator.dto.response.StoryboardSceneResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardSceneRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class StoryboardService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardService.class);

    private static final String CONTENT_TYPE_JPEG = "image/jpeg";
    private static final String ASSET_TYPE_STORYBOARD_IMAGE = "STORYBOARD_IMAGE";
    private static final String ASSET_TYPE_PRODUCTION_IMAGE_ANCHOR = "PRODUCT_VISUAL_ANCHOR";
    private static final String ASSET_TYPE_LIGHTING_BUILD_SHEET_IMAGE = "LIGHTING_BUILD_SHEET_IMAGE";
    private static final String ASSET_TYPE_CAMERA_PLAN_SHEET_IMAGE = "CAMERA_PLAN_SHEET_IMAGE";

    private final CreatorScriptRepository scriptRepository;
    private final CreatorScriptShotRepository scriptShotRepository;
    private final CreatorPromptRunRepository promptRunRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final CreatorStoryboardRepository storyboardRepository;
    private final CreatorStoryboardSceneRepository sceneRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final StoryboardImageGenerationService storyboardImageGenerationService;
    private final ProductionPlanTagService productionPlanTagService;
    private final ScriptStructureService scriptStructureService;
    private final GenerationJobService generationJobService;
    private final CreatorAiService creatorAiService;
    private final CreatorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public StoryboardService(
            CreatorScriptRepository scriptRepository,
            CreatorScriptShotRepository scriptShotRepository,
            CreatorPromptRunRepository promptRunRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            CreatorStoryboardRepository storyboardRepository,
            CreatorStoryboardSceneRepository sceneRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            StoryboardImageGenerationService storyboardImageGenerationService,
            ProductionPlanTagService productionPlanTagService,
            ScriptStructureService scriptStructureService,
            GenerationJobService generationJobService,
            CreatorAiService creatorAiService,
            CreatorProperties properties,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder
    ) {
        this.scriptRepository = scriptRepository;
        this.scriptShotRepository = scriptShotRepository;
        this.promptRunRepository = promptRunRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.storyboardRepository = storyboardRepository;
        this.sceneRepository = sceneRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.storyboardImageGenerationService = storyboardImageGenerationService;
        this.productionPlanTagService = productionPlanTagService;
        this.scriptStructureService = scriptStructureService;
        this.generationJobService = generationJobService;
        this.creatorAiService = creatorAiService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Transactional
    public StoryboardResponse generateFromFinalScript(
            UUID scriptId,
            GenerateStoryboardRequest request,
            String tenantId,
            String userId
    ) {
        PreparedStoryboardGeneration prepared = prepareStoryboardGeneration(scriptId, request, tenantId, userId);
        CreatorGenerationJob generationJob = startStoryboardGenerationJob(prepared);
        return runPreparedStoryboardGeneration(prepared, generationJob.getId());
    }

    @Transactional
    public CreatorGenerationJob startGenerateFromFinalScriptJob(
            UUID scriptId,
            GenerateStoryboardRequest request,
            String tenantId,
            String userId
    ) {
        PreparedStoryboardGeneration prepared = prepareStoryboardGeneration(scriptId, request, tenantId, userId);
        return startStoryboardGenerationJob(prepared);
    }

    @Transactional
    public StoryboardResponse runGenerateFromFinalScriptJob(
            UUID generationJobId,
            UUID scriptId,
            GenerateStoryboardRequest request,
            String tenantId,
            String userId
    ) {
        PreparedStoryboardGeneration prepared = prepareStoryboardGeneration(scriptId, request, tenantId, userId);
        return runPreparedStoryboardGeneration(prepared, generationJobId);
    }

    @Transactional
    public StoryboardSceneResponse generateShotImage(
            UUID scriptId,
            int shotNumber,
            String imageKind,
            GenerateStoryboardRequest request,
            String tenantId,
            String userId
    ) {
        PreparedStoryboardGeneration prepared = prepareStoryboardGeneration(scriptId, request, tenantId, userId);
        CreatorScript script = prepared.script();
        String normalizedKind = assetKeyType(imageKind);
        Map<String, Object> shot = shotByNumber(prepared.shots(), shotNumber);
        if (shot.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shot " + shotNumber + " was not found in the screenplay.");
        }
        CreatorScriptShotPlan plan = shotPlanRepository
                .findByScriptIdAndShotNumberAndStyleKey(script.getId(), shotNumber, ProductionPlanTagService.DEFAULT_STYLE_KEY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generate shot plan JSON before rendering shot images."));

        Map<String, Object> sourceTag = switch (normalizedKind) {
            case "lighting" -> plan.getLightingBuildSheetTag();
            case "dp" -> plan.getCameraPlanSheetTag();
            default -> plan.getStoryboardTag();
        };
        if (sourceTag == null || sourceTag.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The " + normalizedKind + " JSON tag is missing for shot " + shotNumber + ".");
        }

        CreatorStoryboard storyboard = findOrCreateStoryboard(script, prepared.shots(), prepared.screenType(), prepared.renderSize());
        Map<Integer, String> shotIdByNumber = loadShotIdByNumber(script.getId());
        String shotId = defaultString(shotIdByNumber.get(shotNumber), "shot-%04d".formatted(shotNumber));
        Duration signedUrlTtl = prepared.signedUrlTtl();
        RenderSize renderSize = prepared.renderSize();
        String screenType = prepared.screenType();

        CreatorStoryboardScene scene = upsertScene(toScene(
                storyboard.getId(),
                null,
                null,
                null,
                shot,
                plan,
                shotNumber,
                "",
                screenType,
                renderSize
        ));

        CreatorAsset storyboardAsset = findAsset(scene.getImageAssetId());
        CreatorAsset productionImageAsset = findAsset(uuidValue(scene.getMetadata().get("productionImageAssetId")));
        CreatorAsset lightingAsset = findAsset(uuidValue(scene.getMetadata().get("lightingImageAssetId")));
        CreatorAsset cameraPlanAsset = findAsset(uuidValue(scene.getMetadata().get("cameraPlanImageAssetId")));
        String storyboardSignedUrl = storyboardAsset == null ? null : storyboardAsset.getPublicUrl();
        String productionImageSignedUrl = productionImageAsset == null ? null : productionImageAsset.getPublicUrl();
        String lightingSignedUrl = lightingAsset == null ? null : lightingAsset.getPublicUrl();
        String cameraPlanSignedUrl = cameraPlanAsset == null ? null : cameraPlanAsset.getPublicUrl();
        String generatedPrompt = "";

        if ("production".equals(normalizedKind)) {
            String prompt = buildProductionImagePrompt(script.getScriptPayload(), shot, sourceTag, screenType, renderSize, request);
            generatedPrompt = prompt;
            GeneratedAsset generatedAsset = generateProductionImageAsset(script, storyboard.getId(), shot, shotNumber, shotId, screenType, renderSize, signedUrlTtl, prompt, request);
            collectImageUsage(
                    "PRODUCTION_IMAGE_ANCHOR_GENERATE",
                    mapValue(generatedAsset.asset().getMetadata().get("imageGeneration")),
                    script,
                    null,
                    null,
                    "Generated production image anchor for shot " + shotNumber
            );
            productionImageAsset = generatedAsset.asset();
            productionImageSignedUrl = generatedAsset.signedUrl();
            putIfPresent(scene.getMetadata(), "productionImageAssetId", productionImageAsset.getId().toString());
            scene.getMetadata().put("productionImagePrompt", prompt);
        } else if ("storyboard".equals(normalizedKind)) {
            String basePrompt = buildStoryboardPrompt(script.getScriptPayload(), shot, sourceTag, plan.getLightingBuildSheetTag(), plan.getCameraPlanSheetTag(), screenType, renderSize);
            String confirmedRevisionPrompt = request == null ? "" : defaultString(request.imagePrompt(), "").trim();
            String prompt = confirmedRevisionPrompt.isBlank()
                    ? basePrompt
                    : basePrompt + "\n\n[CLIENT-CONFIRMED FRAME REVISION]\n" + confirmedRevisionPrompt;
            generatedPrompt = prompt;
            GeneratedAsset generatedAsset = generateStoryboardAsset(script, storyboard.getId(), shot, shotNumber, shotId, screenType, renderSize, signedUrlTtl, prompt, request);
            collectImageUsage(
                    "STORYBOARD_IMAGE_GENERATE",
                    mapValue(generatedAsset.asset().getMetadata().get("imageGeneration")),
                    script,
                    null,
                    null,
                    "Generated storyboard image for shot " + shotNumber
            );
            storyboardAsset = generatedAsset.asset();
            storyboardSignedUrl = generatedAsset.signedUrl();
            scene.setImageAssetId(storyboardAsset.getId());
            scene.setSketchPrompt(prompt);
        } else {
            String assetType = "lighting".equals(normalizedKind)
                    ? ASSET_TYPE_LIGHTING_BUILD_SHEET_IMAGE
                    : ASSET_TYPE_CAMERA_PLAN_SHEET_IMAGE;
            GeneratedAsset generatedAsset = generateSheetAsset(
                    script,
                    storyboard.getId(),
                    shot,
                    shotNumber,
                    shotId,
                    screenType,
                    renderSize,
                    signedUrlTtl,
                    normalizedKind,
                    assetType,
                    sourceTag
            );
            generatedPrompt = stringValue(sourceTag);
            collectImageUsage(
                    "lighting".equals(normalizedKind)
                            ? "LIGHTING_BUILD_SHEET_IMAGE_GENERATE"
                            : "CAMERA_PLAN_SHEET_IMAGE_GENERATE",
                    mapValue(generatedAsset.asset().getMetadata().get("imageGeneration")),
                    script,
                    null,
                    null,
                    ("lighting".equals(normalizedKind)
                            ? "Generated lighting build sheet for shot "
                            : "Generated DP camera plan sheet for shot ") + shotNumber
            );
            if ("lighting".equals(normalizedKind)) {
                lightingAsset = generatedAsset.asset();
                lightingSignedUrl = generatedAsset.signedUrl();
                putIfPresent(scene.getMetadata(), "lightingImageAssetId", lightingAsset.getId().toString());
            } else {
                cameraPlanAsset = generatedAsset.asset();
                cameraPlanSignedUrl = generatedAsset.signedUrl();
                putIfPresent(scene.getMetadata(), "cameraPlanImageAssetId", cameraPlanAsset.getId().toString());
            }
        }

        CreatorAsset generatedPlanAsset = switch (normalizedKind) {
            case "production" -> productionImageAsset;
            case "lighting" -> lightingAsset;
            case "dp" -> cameraPlanAsset;
            default -> storyboardAsset;
        };
        String generatedPlanAssetUrl = switch (normalizedKind) {
            case "production" -> productionImageSignedUrl;
            case "lighting" -> lightingSignedUrl;
            case "dp" -> cameraPlanSignedUrl;
            default -> storyboardSignedUrl;
        };
        recordGeneratedPlanAsset(
                plan,
                normalizedKind,
                generatedPlanAsset,
                generatedPlanAssetUrl,
                generatedPrompt
        );
        scene.getMetadata().put("storyboardTag", plan.getStoryboardTag());
        scene.getMetadata().put("lightingBuildSheetTag", plan.getLightingBuildSheetTag());
        scene.getMetadata().put("cameraPlanSheetTag", plan.getCameraPlanSheetTag());
        scene = sceneRepository.save(scene);
        linkProjectSelectedStoryboard(storyboard);
        CreatorAsset responseImageAsset = "production".equals(normalizedKind) ? productionImageAsset : storyboardAsset;
        String responseImageSignedUrl = "production".equals(normalizedKind) ? productionImageSignedUrl : storyboardSignedUrl;
        return toResponse(scene, responseImageAsset, responseImageSignedUrl, lightingAsset, lightingSignedUrl, cameraPlanAsset, cameraPlanSignedUrl, plan);
    }

    @Transactional
    public StoryboardSceneResponse editShotWithAi(
            UUID scriptId,
            int shotNumber,
            ShotAiEditRequest request,
            String tenantId,
            String userId
    ) {
        if (request == null || request.instruction() == null || request.instruction().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tell AI what to change in this shot.");
        }
        String normalizedKind = assetKeyType(request.imageKind());
        if (!"storyboard".equals(normalizedKind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI shot edits currently regenerate the storyboard shot image only.");
        }

        PreparedStoryboardGeneration prepared = prepareStoryboardGeneration(
                scriptId,
                new GenerateStoryboardRequest(request.screenType(), request.signedUrlTtlSeconds(), null, null, null, null, null, null, null),
                tenantId,
                userId
        );
        CreatorScript script = prepared.script();
        CreatorStoryboard storyboard = findOrCreateStoryboard(script, prepared.shots(), prepared.screenType(), prepared.renderSize());
        Map<String, Object> shot = shotByNumber(prepared.shots(), shotNumber);
        CreatorStoryboardScene existingScene = sceneRepository.findByStoryboardIdAndShotNumber(storyboard.getId(), shotNumber).orElse(null);
        if (shot.isEmpty() && existingScene != null) {
            shot = sceneToShotMap(existingScene);
        }
        if (shot.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shot " + shotNumber + " was not found in the screenplay or storyboard timeline.");
        }

        CreatorScriptShotPlan plan = shotPlanRepository
                .findByScriptIdAndShotNumberAndStyleKey(script.getId(), shotNumber, ProductionPlanTagService.DEFAULT_STYLE_KEY)
                .orElse(null);
        Map<Integer, String> shotIdByNumber = loadShotIdByNumber(script.getId());
        String shotId = defaultString(shotIdByNumber.get(shotNumber), shotIdFromScene(existingScene, shotNumber));
        Map<Integer, CreatorScriptShotPlan> planByShotNumber = loadPlanByShotNumber(script.getId());
        Map<String, Object> continuityContext = shotContinuityContext(prepared.shots(), planByShotNumber, shotNumber);
        addStoryboardSceneContinuity(continuityContext, storyboard.getId(), shotNumber);

        Map<String, Object> editedShot = generateEditedShotJson(
                script,
                shot,
                plan,
                request.instruction().trim(),
                continuityContext
        );
        script = persistEditedShotJson(script, editedShot, shotNumber);
        productionPlanTagService.generateTagsForScript(
                script,
                script.getScriptPayload(),
                List.of(editedShot),
                ProductionPlanTagService.DEFAULT_STYLE_KEY
        );
        plan = shotPlanRepository
                .findByScriptIdAndShotNumberAndStyleKey(script.getId(), shotNumber, ProductionPlanTagService.DEFAULT_STYLE_KEY)
                .orElse(null);

        CreatorStoryboardScene scene = upsertScene(toScene(
                storyboard.getId(),
                null,
                null,
                null,
                editedShot,
                plan,
                shotNumber,
                "",
                prepared.screenType(),
                prepared.renderSize()
        ));

        Map<String, Object> storyboardTag = plan == null ? Map.of() : plan.getStoryboardTag();
        String basePrompt = buildStoryboardPrompt(
                script.getScriptPayload(),
                editedShot,
                storyboardTag,
                plan == null ? Map.of() : plan.getLightingBuildSheetTag(),
                plan == null ? Map.of() : plan.getCameraPlanSheetTag(),
                prepared.screenType(),
                prepared.renderSize()
        );
        String prompt = buildAiShotEditPrompt(basePrompt, "edit_existing_shot", request.instruction(), continuityContext);
        GeneratedAsset generatedAsset = generateStoryboardAsset(
                script,
                storyboard.getId(),
                editedShot,
                shotNumber,
                shotId,
                prepared.screenType(),
                prepared.renderSize(),
                prepared.signedUrlTtl(),
                prompt,
                null
        );
        collectImageUsage(
                "SHOT_STORYBOARD_IMAGE_EDIT_GENERATE",
                mapValue(generatedAsset.asset().getMetadata().get("imageGeneration")),
                script,
                null,
                null,
                "Generated edited storyboard image for shot " + shotNumber
        );

        scene.setImageAssetId(generatedAsset.asset().getId());
        scene.setSketchPrompt(prompt);
        scene.getMetadata().put("storyboardTag", storyboardTag);
        scene.getMetadata().put("lightingBuildSheetTag", plan == null ? Map.of() : plan.getLightingBuildSheetTag());
        scene.getMetadata().put("cameraPlanSheetTag", plan == null ? Map.of() : plan.getCameraPlanSheetTag());
        scene.getMetadata().put("rawShot", editedShot);
        scene.getMetadata().put("aiShotEdit", aiEditMetadata("edit_existing_shot", request.instruction(), continuityContext));
        scene = sceneRepository.save(scene);
        linkProjectSelectedStoryboard(storyboard);

        CreatorAsset lightingAsset = findAsset(uuidValue(scene.getMetadata().get("lightingImageAssetId")));
        CreatorAsset cameraPlanAsset = findAsset(uuidValue(scene.getMetadata().get("cameraPlanImageAssetId")));
        return toResponse(
                scene,
                generatedAsset.asset(),
                generatedAsset.signedUrl(),
                lightingAsset,
                signedUrlFor(lightingAsset),
                cameraPlanAsset,
                signedUrlFor(cameraPlanAsset),
                plan
        );
    }

    @Transactional
    public StoryboardSceneResponse insertTimelineShot(
            UUID scriptId,
            ShotTimelineInsertRequest request,
            String tenantId,
            String userId
    ) {
        if (request == null || request.instruction() == null || request.instruction().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Describe the timeline shot you want to add.");
        }
        PreparedStoryboardGeneration prepared = prepareStoryboardGeneration(
                scriptId,
                new GenerateStoryboardRequest(request.screenType(), request.signedUrlTtlSeconds(), null, null, null, null, null, null, null),
                tenantId,
                userId
        );
        CreatorScript script = prepared.script();
        CreatorStoryboard storyboard = findOrCreateStoryboard(script, prepared.shots(), prepared.screenType(), prepared.renderSize());
        int maxKnownShotNumber = Math.max(maxShotNumber(prepared.shots()), maxStoryboardShotNumber(storyboard.getId()));
        int afterShotNumber = Math.max(0, Math.min(defaultInt(request.afterShotNumber(), maxKnownShotNumber), maxKnownShotNumber));
        int insertShotNumber = afterShotNumber + 1;
        Map<Integer, CreatorScriptShotPlan> planByShotNumber = loadPlanByShotNumber(script.getId());
        Map<String, Object> continuityContext = timelineContinuityContext(
                prepared.shots(),
                planByShotNumber,
                afterShotNumber,
                0,
                insertShotNumber
        );
        addStoryboardSceneContinuity(continuityContext, storyboard.getId(), insertShotNumber);
        Map<String, Object> insertedShot = buildInsertedTimelineShot(request, prepared.shots(), afterShotNumber, insertShotNumber);
        Map<String, Object> insertedStoryboardTag = buildInsertedStoryboardTag(insertedShot, request.instruction(), continuityContext);

        shiftStoryboardScenesForInsert(storyboard.getId(), insertShotNumber);

        String shotId = "inserted-shot-%04d-%s".formatted(insertShotNumber, UUID.randomUUID().toString().substring(0, 8));
        String basePrompt = buildStoryboardPrompt(
                script.getScriptPayload(),
                insertedShot,
                insertedStoryboardTag,
                Map.of(),
                Map.of(),
                prepared.screenType(),
                prepared.renderSize()
        );
        String prompt = buildAiShotEditPrompt(basePrompt, "insert_timeline_shot", request.instruction(), continuityContext);
        GeneratedAsset generatedAsset = generateStoryboardAsset(
                script,
                storyboard.getId(),
                insertedShot,
                insertShotNumber,
                shotId,
                prepared.screenType(),
                prepared.renderSize(),
                prepared.signedUrlTtl(),
                prompt,
                null
        );
        collectImageUsage(
                "SHOT_TIMELINE_IMAGE_GENERATE",
                mapValue(generatedAsset.asset().getMetadata().get("imageGeneration")),
                script,
                null,
                null,
                "Generated inserted timeline storyboard image for shot " + insertShotNumber
        );
        CreatorStoryboardScene scene = toScene(
                storyboard.getId(),
                generatedAsset.asset().getId(),
                null,
                null,
                insertedShot,
                null,
                insertShotNumber,
                prompt,
                prepared.screenType(),
                prepared.renderSize()
        );
        scene.getMetadata().put("insertedTimelineShot", true);
        scene.getMetadata().put("storyboardTag", insertedStoryboardTag);
        scene.getMetadata().put("lightingBuildSheetTag", Map.of());
        scene.getMetadata().put("cameraPlanSheetTag", Map.of());
        scene.getMetadata().put("aiShotEdit", aiEditMetadata("insert_timeline_shot", request.instruction(), continuityContext));
        scene = upsertScene(scene);

        storyboard.setTotalShots(Math.max(defaultInt(storyboard.getTotalShots(), 0), maxStoryboardShotNumber(storyboard.getId())));
        storyboard.setStatus("GENERATED");
        storyboardRepository.save(storyboard);
        linkProjectSelectedStoryboard(storyboard);
        return toResponse(scene, generatedAsset.asset(), generatedAsset.signedUrl(), null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<ShotImageUrlResponse> listShotImageUrls(UUID scriptId, String tenantId, String userId) {
        CreatorScript script = scriptRepository
                .findByIdAndTenantIdAndUserId(
                        scriptId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator script was not found."));

        Map<Integer, ShotImageAssets> imagesByShot = new LinkedHashMap<>();
        for (CreatorAsset asset : assetRepository.findShotImageAssetsForScript(script.getId(), script.getTenantId(), script.getUserId())) {
            Integer shotNumber = intValue(asset.getMetadata().get("shotNumber"), null);
            if (shotNumber == null) {
                continue;
            }
            ShotImageAssets imageAssets = imagesByShot.computeIfAbsent(shotNumber, ignored -> new ShotImageAssets());
            String kind = assetKeyType(defaultString(stringValue(asset.getMetadata().get("imageKind")), asset.getAssetType()));
            if ("production".equals(kind) && imageAssets.production == null) {
                imageAssets.production = asset;
            } else if ("lighting".equals(kind) && imageAssets.lighting == null) {
                imageAssets.lighting = asset;
            } else if ("dp".equals(kind) && imageAssets.cameraPlan == null) {
                imageAssets.cameraPlan = asset;
            } else if ("storyboard".equals(kind) && imageAssets.storyboard == null) {
                imageAssets.storyboard = asset;
            }
        }

        return imagesByShot.entrySet()
                .stream()
                .map(entry -> toShotImageUrlResponse(script, entry.getKey(), entry.getValue()))
                .toList();
    }

    private StoryboardResponse runPreparedStoryboardGeneration(PreparedStoryboardGeneration prepared, UUID generationJobId) {
        CreatorScript script = prepared.script();
        List<Map<String, Object>> shots = prepared.shots();
        String screenType = prepared.screenType();
        RenderSize renderSize = prepared.renderSize();
        Duration signedUrlTtl = prepared.signedUrlTtl();
        if (generationJobId != null && !generationJobService.claimGenerationJobExecution(generationJobId, "storyboard-generation")) {
            log.info(
                    "Skipping duplicate storyboard execution jobId={} scriptId={} tenantId={} userId={}",
                    generationJobId,
                    script == null ? null : script.getId(),
                    script == null ? null : script.getTenantId(),
                    script == null ? null : script.getUserId()
            );
            return null;
        }
        try {
            Map<Integer, CreatorScriptShotPlan> planByShotNumber = loadPlanByShotNumber(script.getId());
            if (planByShotNumber.size() < shots.size()) {
                productionPlanTagService.generateTagsForScript(script, script.getScriptPayload(), shots, ProductionPlanTagService.DEFAULT_STYLE_KEY, prepared.videoModelCapability());
                planByShotNumber = loadPlanByShotNumber(script.getId());
            }
            CreatorStoryboard storyboard = storyboardRepository.saveAndFlush(CreatorStoryboard.builder()
                    .tenantId(script.getTenantId())
                    .userId(script.getUserId())
                    .projectId(script.getProjectId())
                    .ideaId(script.getStoryIdeaId())
                    .title(defaultString(script.getTitle(), "Storyboard"))
                    .durationSeconds(defaultInt(script.getDurationSeconds(), totalDuration(shots)))
                    .totalShots(shots.size())
                    .pacingStyle(stringValue(script.getScriptPayload().get("pacingStyle")))
                    .emotionalArc(stringValue(script.getScriptPayload().get("emotionalArc")))
                    .hookStrategy(stringValue(script.getScriptPayload().get("hookStrategy")))
                    .creatorFitReasoning(stringValue(script.getScriptPayload().get("creatorFitReasoning")))
                    .audienceFitReasoning(stringValue(script.getScriptPayload().get("audienceFitReasoning")))
                    .overallExecutionDifficulty(stringValue(script.getScriptPayload().get("overallExecutionDifficulty")))
                    .status("GENERATED")
                    .metadata(storyboardMetadata(script, generationJobId, screenType, renderSize, prepared.videoModelCapability()))
                    .build());

            Map<Integer, String> shotIdByNumber = loadShotIdByNumber(script.getId());
            List<StoryboardSceneResponse> sceneResponses = new ArrayList<>();
            List<Map<String, Object>> imageCostMetadataItems = new ArrayList<>();
            publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, 8, "Storyboard pack created");
            for (int index = 0; index < shots.size(); index++) {
                Map<String, Object> shot = shots.get(index);
                List<String> storyboardReferenceUrls = storyboardReferenceImageUrls(script, shot);
                List<StoryboardImageGenerationService.ReferenceImageInput> storyboardReferences =
                        downloadStoryboardReferenceImages(
                                storyboardReferenceUrls,
                                storyboardReferenceImageAssets(script, shot)
                        );
                int shotNumber = intValue(shot.get("shotNumber"), index + 1);
                String shotId = defaultString(shotIdByNumber.get(shotNumber), "shot-%04d".formatted(shotNumber));
                CreatorScriptShotPlan plan = planByShotNumber.get(shotNumber);
                String prompt = buildStoryboardPrompt(
                        script.getScriptPayload(),
                        shot,
                        plan == null ? Map.of() : plan.getStoryboardTag(),
                        plan == null ? Map.of() : plan.getLightingBuildSheetTag(),
                        plan == null ? Map.of() : plan.getCameraPlanSheetTag(),
                        screenType,
                        renderSize
                );
                publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, Math.max(9, progressFor(index, shots.size(), 0)), "Generating storyboard image for shot " + shotNumber);
                GeneratedStoryboardImage generatedImage = generateStoryboardImage(
                        shot,
                        screenType,
                        renderSize,
                        prompt,
                        storyboardReferences,
                        storyboardReferenceUrls
                );
                collectImageUsage(
                        "STORYBOARD_IMAGE_GENERATE",
                        generatedImage.metadata(),
                        script,
                        generationJobId,
                        imageCostMetadataItems,
                        "Generated storyboard image for shot " + shotNumber
                );
                byte[] imageBytes = generatedImage.bytes();
                String objectKey = objectKey(script, storyboard.getId(), shotId, "storyboard");

                AssetStorageService.StoredObject storedObject = assetStorageService.uploadCreatorAsset(
                        objectKey,
                        imageBytes,
                        CONTENT_TYPE_JPEG,
                        signedUrlTtl
                );

                CreatorAsset asset = upsertAsset(CreatorAsset.builder()
                        .tenantId(script.getTenantId())
                        .userId(script.getUserId())
                        .projectId(script.getProjectId())
                        .storyboardId(storyboard.getId())
                        .assetType(ASSET_TYPE_STORYBOARD_IMAGE)
                        .bucket(storedObject.bucket())
                        .objectKey(storedObject.objectKey())
                        .contentType(storedObject.contentType())
                        .sizeBytes(storedObject.sizeBytes())
                        .publicUrl(storedObject.signedUrl())
                        .metadata(assetMetadata(script, storyboard.getId(), shotId, shotNumber, "storyboard", screenType, renderSize, signedUrlTtl, generatedImage.metadata()))
                        .build());

                CreatorStoryboardScene scene = upsertScene(toScene(
                        storyboard.getId(),
                        asset.getId(),
                        null,
                        null,
                        shot,
                        plan,
                        shotNumber,
                        prompt,
                        screenType,
                        renderSize
                ));
                CreatorAsset lightingAsset = null;
                String lightingSignedUrl = null;
                CreatorAsset cameraPlanAsset = null;
                String cameraPlanSignedUrl = null;
                StoryboardSceneResponse sceneResponse = toResponse(scene, asset, storedObject.signedUrl(), lightingAsset, lightingSignedUrl, cameraPlanAsset, cameraPlanSignedUrl, plan);
                sceneResponses.add(sceneResponse);
                publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, progressFor(index, shots.size(), 1), "Storyboard image ready for shot " + shotNumber);
                if (plan != null) {
                    publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, progressFor(index, shots.size(), 1), "Generating lighting sheet for shot " + shotNumber);
                    GeneratedAsset lightingGenerated = generateSheetAsset(
                            script,
                            storyboard.getId(),
                            shot,
                            shotNumber,
                            shotId,
                            screenType,
                            renderSize,
                            signedUrlTtl,
                            "lighting",
                            ASSET_TYPE_LIGHTING_BUILD_SHEET_IMAGE,
                            plan.getLightingBuildSheetTag()
                    );
                    lightingAsset = lightingGenerated.asset();
                    lightingSignedUrl = lightingGenerated.signedUrl();
                    collectImageUsage(
                            "LIGHTING_BUILD_SHEET_IMAGE_GENERATE",
                            mapValue(lightingAsset.getMetadata().get("imageGeneration")),
                            script,
                            generationJobId,
                            imageCostMetadataItems,
                            "Generated lighting build sheet for shot " + shotNumber
                    );
                    putIfPresent(scene.getMetadata(), "lightingImageAssetId", lightingAsset.getId().toString());
                    scene = sceneRepository.save(scene);
                    replaceSceneResponse(sceneResponses, toResponse(scene, asset, storedObject.signedUrl(), lightingAsset, lightingSignedUrl, cameraPlanAsset, cameraPlanSignedUrl, plan));
                    publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, progressFor(index, shots.size(), 2), "Lighting sheet ready for shot " + shotNumber);

                    publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, progressFor(index, shots.size(), 2), "Generating DP camera plan for shot " + shotNumber);
                    GeneratedAsset cameraGenerated = generateSheetAsset(
                            script,
                            storyboard.getId(),
                            shot,
                            shotNumber,
                            shotId,
                            screenType,
                            renderSize,
                            signedUrlTtl,
                            "dp",
                            ASSET_TYPE_CAMERA_PLAN_SHEET_IMAGE,
                            plan.getCameraPlanSheetTag()
                    );
                    cameraPlanAsset = cameraGenerated.asset();
                    cameraPlanSignedUrl = cameraGenerated.signedUrl();
                    collectImageUsage(
                            "CAMERA_PLAN_SHEET_IMAGE_GENERATE",
                            mapValue(cameraPlanAsset.getMetadata().get("imageGeneration")),
                            script,
                            generationJobId,
                            imageCostMetadataItems,
                            "Generated DP camera plan sheet for shot " + shotNumber
                    );
                    putIfPresent(scene.getMetadata(), "cameraPlanImageAssetId", cameraPlanAsset.getId().toString());
                    scene = sceneRepository.save(scene);
                    replaceSceneResponse(sceneResponses, toResponse(scene, asset, storedObject.signedUrl(), lightingAsset, lightingSignedUrl, cameraPlanAsset, cameraPlanSignedUrl, plan));
                    publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, progressFor(index, shots.size(), 3), "DP camera plan ready for shot " + shotNumber);
                }
            }

            linkProjectSelectedStoryboard(storyboard);
            StoryboardResponse response = new StoryboardResponse(
                    storyboard.getId(),
                    script.getId(),
                    storyboard.getProjectId(),
                    storyboard.getIdeaId(),
                    storyboard.getTitle(),
                    screenType,
                    renderSize.width(),
                    renderSize.height(),
                    storyboard.getDurationSeconds(),
                    storyboard.getTotalShots(),
                    storyboard.getStatus(),
                    sceneResponses,
                    storyboard.getCreatedAt()
            );
            Map<String, Object> jobOutput = new LinkedHashMap<>();
            jobOutput.put("storyboardId", storyboard.getId().toString());
            jobOutput.put("scriptId", script.getId().toString());
            jobOutput.put("screenplayId", script.getId().toString());
            jobOutput.put("sceneCount", sceneResponses.size());
            jobOutput.put("screenType", screenType);
            jobOutput.put("renderWidth", renderSize.width());
            jobOutput.put("renderHeight", renderSize.height());
            jobOutput.put("imageProvider", properties.getAi().isStoryboardImageGenerationEnabled() ? "gemini" : "local");
            jobOutput.put("imageModel", properties.getAi().isStoryboardImageGenerationEnabled() ? properties.getAi().getGeminiImageModel() : "local_storyboard_sketch_v1");
            jobOutput.put("steps", storyboardGenerationSteps(100, "Storyboard generation complete", sceneResponses, storyboard.getTotalShots()));
            Map<String, Object> aggregateImageCostMetadata = aggregateImageCostMetadata(imageCostMetadataItems);
            if (!aggregateImageCostMetadata.isEmpty()) {
                jobOutput.put("costMetadata", aggregateImageCostMetadata);
            }
            jobOutput.put("storyboard", toMap(response));
            generationJobService.completeGenerationJob(generationJobId, jobOutput);

            return response;
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(generationJobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private PreparedStoryboardGeneration prepareStoryboardGeneration(
            UUID scriptId,
            GenerateStoryboardRequest request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorScript script = scriptRepository.findByIdAndTenantIdAndUserId(scriptId, safeTenantId, safeUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Final script was not found."));

        ProductionPlanTagService.VideoModelCapability videoModelCapability = storyboardVideoModelCapability(request);
        List<Map<String, Object>> baseShots = scriptShots(script);
        List<Map<String, Object>> shots = hasStoryboardVideoCapability(request)
                ? storyboardShotsForModelCapability(baseShots, videoModelCapability.maxClipSeconds())
                : baseShots;
        if (shots.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Final script does not have shots for storyboard generation.");
        }

        String screenType = normalizeScreenType(defaultString(
                request == null ? null : request.screenType(),
                defaultString(script.getScreenType(), stringValue(script.getScriptPayload().get("screenType")))
        ));
        RenderSize renderSize = renderSize(screenType);
        Duration signedUrlTtl = signedUrlTtl(request == null ? null : request.signedUrlTtlSeconds());
        return new PreparedStoryboardGeneration(script, shots, screenType, renderSize, signedUrlTtl, videoModelCapability);
    }

    private CreatorGenerationJob startStoryboardGenerationJob(PreparedStoryboardGeneration prepared) {
        CreatorScript script = prepared.script();
        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("scriptId", script.getId().toString());
        jobInput.put("screenplayId", script.getId().toString());
        jobInput.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        jobInput.put("storyIdeaId", script.getStoryIdeaId() == null ? null : script.getStoryIdeaId().toString());
        jobInput.put("screenType", prepared.screenType());
        jobInput.put("renderWidth", prepared.renderSize().width());
        jobInput.put("renderHeight", prepared.renderSize().height());
        jobInput.put("shotCount", prepared.shots().size());
        jobInput.put("videoModelCapability", videoModelCapabilityMap(prepared.videoModelCapability()));
        jobInput.put("imageProvider", properties.getAi().isStoryboardImageGenerationEnabled() ? "gemini" : "local");
        jobInput.put("imageModel", properties.getAi().isStoryboardImageGenerationEnabled() ? properties.getAi().getGeminiImageModel() : "local_storyboard_sketch_v1");

        return generationJobService.startGenerationJob(
                PromptTemplateType.STORYBOARD_GENERATE.name(),
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                jobInput
        );
    }

    private CreatorStoryboard findOrCreateStoryboard(CreatorScript script, List<Map<String, Object>> shots, String screenType, RenderSize renderSize) {
        if (script.getProjectId() != null && script.getStoryIdeaId() != null) {
            var existing = storyboardRepository.findTopByProjectIdAndIdeaIdAndTenantIdAndUserIdOrderByCreatedAtDesc(
                    script.getProjectId(),
                    script.getStoryIdeaId(),
                    script.getTenantId(),
                    script.getUserId()
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        if (script.getStoryIdeaId() != null) {
            var existing = storyboardRepository.findTopByIdeaIdAndTenantIdAndUserIdOrderByCreatedAtDesc(
                    script.getStoryIdeaId(),
                    script.getTenantId(),
                    script.getUserId()
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        CreatorStoryboard storyboard = storyboardRepository.saveAndFlush(CreatorStoryboard.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .ideaId(script.getStoryIdeaId())
                .title(defaultString(script.getTitle(), "Storyboard"))
                .durationSeconds(defaultInt(script.getDurationSeconds(), totalDuration(shots)))
                .totalShots(shots.size())
                .pacingStyle(stringValue(script.getScriptPayload().get("pacingStyle")))
                .emotionalArc(stringValue(script.getScriptPayload().get("emotionalArc")))
                .hookStrategy(stringValue(script.getScriptPayload().get("hookStrategy")))
                .creatorFitReasoning(stringValue(script.getScriptPayload().get("creatorFitReasoning")))
                .audienceFitReasoning(stringValue(script.getScriptPayload().get("audienceFitReasoning")))
                .overallExecutionDifficulty(stringValue(script.getScriptPayload().get("overallExecutionDifficulty")))
                .status("PLANNED")
                .metadata(storyboardMetadata(script, UUID.randomUUID(), screenType, renderSize))
                .build());
        linkProjectSelectedStoryboard(storyboard);
        return storyboard;
    }

    private Map<String, Object> shotByNumber(List<Map<String, Object>> shots, int shotNumber) {
        for (int index = 0; index < (shots == null ? 0 : shots.size()); index++) {
            Map<String, Object> shot = shots.get(index);
            int current = intValue(shot.get("shotNumber"), index + 1);
            if (current == shotNumber) {
                return shot;
            }
        }
        return Map.of();
    }

    private Map<String, Object> generateEditedShotJson(
            CreatorScript script,
            Map<String, Object> originalShot,
            CreatorScriptShotPlan plan,
            String instruction,
            Map<String, Object> continuityContext
    ) {
        String renderedPrompt = buildShotJsonEditPrompt(script, originalShot, plan, instruction, continuityContext);
        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("scriptId", script.getId().toString());
        providerInput.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        providerInput.put("storyIdeaId", script.getStoryIdeaId() == null ? null : script.getStoryIdeaId().toString());
        providerInput.put("instruction", instruction);
        providerInput.put("shot", originalShot);
        providerInput.put("scriptContext", compactScriptContext(script));
        providerInput.put("storyboardTag", plan == null ? Map.of() : plan.getStoryboardTag());
        providerInput.put("lightingBuildSheetTag", plan == null ? Map.of() : plan.getLightingBuildSheetTag());
        providerInput.put("cameraPlanSheetTag", plan == null ? Map.of() : plan.getCameraPlanSheetTag());
        providerInput.put("continuityContext", continuityContext == null ? Map.of() : continuityContext);
        providerInput.put("renderedPrompt", renderedPrompt);

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                null,
                null
        );
        CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(
                PromptTemplateType.SHOT_JSON_EDIT.name(),
                providerInput,
                usageContext
        );
        Map<String, Object> providerOutput = aiResponse.output() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(aiResponse.output());
        log.info(
                "AI shot JSON edit raw response scriptId={} shotNumber={} output={}",
                script.getId(),
                intValue(originalShot.get("shotNumber"), null),
                providerOutput
        );
        Map<String, Object> editedShot = resolveEditedShotPayload(providerOutput, originalShot);
        CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .promptTemplateKey(PromptTemplateType.SHOT_JSON_EDIT.name())
                .promptTemplateVersion(1)
                .renderedPrompt(renderedPrompt)
                .inputSnapshot(providerInput)
                .provider(creatorAiService.providerName())
                .model(creatorAiService.modelName())
                .outputPayload(providerOutput)
                .tokenMetadata(aiResponse.tokenMetadata())
                .costMetadata(aiResponse.costMetadata())
                .status("COMPLETED")
                .completedAt(OffsetDateTime.now())
                .build());
        creatorAiService.publishBillingDebit(
                PromptTemplateType.SHOT_JSON_EDIT.name(),
                aiResponse,
                usageContext.withPromptRunId(promptRun.getId())
        );
        editedShot.put("_aiShotEdit", Map.of(
                "promptRunId", promptRun.getId().toString(),
                "instruction", instruction,
                "editedAt", OffsetDateTime.now().toString()
        ));
        return editedShot;
    }

    private String buildShotJsonEditPrompt(
            CreatorScript script,
            Map<String, Object> originalShot,
            CreatorScriptShotPlan plan,
            String instruction,
            Map<String, Object> continuityContext
    ) {
        return """
                You are editing one shot inside an existing creator screenplay.

                Return exactly one valid JSON object in this shape:
                {
                  "shot": { ...the full updated shot JSON... }
                }

                Rules:
                - Apply the user's edit instruction to the shot JSON itself, not only to image prompt wording.
                - Preserve shotNumber, startTime, endTime, durationSeconds, beatNumber, sequenceNumber, and sceneNumber unless the instruction explicitly requests timing or ordering changes.
                - Keep all important existing keys from the original shot. Update action, title, composition, camera, lighting, blocking, visualTreatment, textOverlay, dialogue, sound, production, safety, and sketchPrompt fields when relevant.
                - Use arrays for array fields like editingNotes, safetyFlags, soundDesign, captionTrack, primaryCharacters, sideCharacters, primaryActors, and sideActors.
                - Use objects for object fields like dialogue, visualTreatment, backgroundMusicCue, resourceRequirements, and postProductionNotes. When visualTreatment is changed, use motionStyle, colorGrade, editorialEffect, and notes.
                - Do not add markdown, prose, comments, or raw JSON strings. Return parseable JSON only.

                User edit instruction:
                %s

                Screenplay context:
                %s

                Original shot JSON:
                %s

                Existing production plan tags for continuity:
                %s

                Adjacent-shot continuity context:
                %s
                """.formatted(
                truncatePromptText(instruction, 1200),
                toJson(compactScriptContext(script)),
                toJson(originalShot == null ? Map.of() : originalShot),
                toJson(Map.of(
                        "storyboardTag", plan == null || plan.getStoryboardTag() == null ? Map.of() : plan.getStoryboardTag(),
                        "lightingBuildSheetTag", plan == null || plan.getLightingBuildSheetTag() == null ? Map.of() : plan.getLightingBuildSheetTag(),
                        "cameraPlanSheetTag", plan == null || plan.getCameraPlanSheetTag() == null ? Map.of() : plan.getCameraPlanSheetTag()
                )),
                toJson(continuityContext == null ? Map.of() : continuityContext)
        );
    }

    private Map<String, Object> compactScriptContext(CreatorScript script) {
        Map<String, Object> payload = script.getScriptPayload() == null ? Map.of() : script.getScriptPayload();
        Map<String, Object> context = new LinkedHashMap<>();
        putIfPresent(context, "projectTitle", firstNonBlank(payload.get("projectTitle"), script.getTitle()));
        putIfPresent(context, "duration", firstNonBlank(payload.get("duration"), script.getDurationSeconds()));
        putIfPresent(context, "durationSeconds", script.getDurationSeconds());
        putIfPresent(context, "screenType", firstNonBlank(payload.get("screenType"), script.getScreenType()));
        putIfPresent(context, "dialogueLanguage", firstNonBlank(payload.get("dialogueLanguage"), script.getDialogueLanguage()));
        putIfPresent(context, "category", firstNonBlank(payload.get("category"), script.getCategoryCode()));
        putIfPresent(context, "inferredTone", payload.get("inferredTone"));
        putIfPresent(context, "continuityBible", payload.get("continuityBible"));
        putIfPresent(context, "characterVoiceProfiles", payload.get("characterVoiceProfiles"));
        putIfPresent(context, "dialogueCallbacks", payload.get("dialogueCallbacks"));
        putIfPresent(context, "backgroundMusicPlan", payload.get("backgroundMusicPlan"));
        putIfPresent(context, "soundDesignPlan", payload.get("soundDesignPlan"));
        return context;
    }

    private Map<String, Object> resolveEditedShotPayload(Map<String, Object> providerOutput, Map<String, Object> originalShot) {
        Map<String, Object> edited = mapValue(providerOutput == null ? null : providerOutput.get("shot"));
        if (edited.isEmpty()) {
            edited = mapValue(providerOutput == null ? null : providerOutput.get("updatedShot"));
        }
        if (edited.isEmpty() && providerOutput != null && !providerOutput.isEmpty() && providerOutput.get("shotNumber") != null) {
            edited = mapValue(providerOutput);
        }
        if (edited.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI shot edit response did not contain a usable shot JSON object.");
        }
        Map<String, Object> original = originalShot == null ? Map.of() : originalShot;
        Map<String, Object> merged = new LinkedHashMap<>(original);
        merged.putAll(edited);
        preserveShotIdentityValue(merged, original, "shotNumber");
        preserveShotIdentityValue(merged, original, "startTime");
        preserveShotIdentityValue(merged, original, "endTime");
        preserveShotIdentityValue(merged, original, "durationSeconds");
        preserveShotIdentityValue(merged, original, "beatNumber");
        preserveShotIdentityValue(merged, original, "sequenceNumber");
        preserveShotIdentityValue(merged, original, "sceneNumber");
        if (stringList(merged.get("editingNotes")).isEmpty()) {
            merged.put("editingNotes", List.of("AI edit applied to shot JSON."));
        }
        return merged;
    }

    private void preserveShotIdentityValue(Map<String, Object> target, Map<String, Object> source, String key) {
        if (target == null || source == null || key == null || !source.containsKey(key)) {
            return;
        }
        target.put(key, source.get(key));
    }

    private CreatorScript persistEditedShotJson(CreatorScript script, Map<String, Object> editedShot, int shotNumber) {
        List<Map<String, Object>> updatedShots = replaceShotByNumber(scriptShots(script), editedShot, shotNumber);
        Map<String, Object> payload = new LinkedHashMap<>(script.getScriptPayload() == null ? Map.of() : script.getScriptPayload());
        payload.put("shots", updatedShots);
        payload.put("totalShots", updatedShots.size());
        payload.putIfAbsent("duration", script.getDurationSeconds());
        payload.put("lastShotJsonEdit", Map.of(
                "shotNumber", shotNumber,
                "editedAt", OffsetDateTime.now().toString(),
                "source", "ai-edit"
        ));
        script.setScriptPayload(payload);
        script.setShots(updatedShots);
        script.setTotalShots(updatedShots.size());
        script.setStatus("EDITED");
        script.setUpdatedAt(OffsetDateTime.now());
        CreatorScript savedScript = scriptRepository.saveAndFlush(script);
        scriptStructureService.syncScreenplayShots(savedScript, payload, updatedShots);
        return savedScript;
    }

    private List<Map<String, Object>> replaceShotByNumber(List<Map<String, Object>> shots, Map<String, Object> replacement, int shotNumber) {
        List<Map<String, Object>> updated = new ArrayList<>();
        boolean replaced = false;
        List<Map<String, Object>> source = shots == null ? List.of() : shots;
        for (int index = 0; index < source.size(); index++) {
            Map<String, Object> current = source.get(index);
            int currentShotNumber = intValue(current == null ? null : current.get("shotNumber"), index + 1);
            if (currentShotNumber == shotNumber) {
                Map<String, Object> shot = new LinkedHashMap<>(replacement == null ? Map.of() : replacement);
                shot.put("shotNumber", shotNumber);
                updated.add(shot);
                replaced = true;
            } else {
                updated.add(current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current));
            }
        }
        if (!replaced) {
            Map<String, Object> shot = new LinkedHashMap<>(replacement == null ? Map.of() : replacement);
            shot.put("shotNumber", shotNumber);
            updated.add(shot);
        }
        updated.sort((left, right) -> Integer.compare(
                intValue(left.get("shotNumber"), 0),
                intValue(right.get("shotNumber"), 0)
        ));
        return updated;
    }

    private CreatorStoryboardScene toScene(
            UUID storyboardId,
            UUID assetId,
            UUID lightingAssetId,
            UUID cameraPlanAssetId,
            Map<String, Object> shot,
            CreatorScriptShotPlan plan,
            int shotNumber,
            String prompt,
            String screenType,
            RenderSize renderSize
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("screenType", screenType);
        metadata.put("renderWidth", renderSize.width());
        metadata.put("renderHeight", renderSize.height());
        metadata.put("rawShot", shot);
        if (plan != null) {
            metadata.put("shotPlanId", plan.getId() == null ? null : plan.getId().toString());
            metadata.put("storyboardTag", plan.getStoryboardTag());
            metadata.put("lightingBuildSheetTag", plan.getLightingBuildSheetTag());
            metadata.put("cameraPlanSheetTag", plan.getCameraPlanSheetTag());
            putIfPresent(metadata, "lightingImageAssetId", lightingAssetId == null ? null : lightingAssetId.toString());
            putIfPresent(metadata, "cameraPlanImageAssetId", cameraPlanAssetId == null ? null : cameraPlanAssetId.toString());
        }

        return CreatorStoryboardScene.builder()
                .storyboardId(storyboardId)
                .imageAssetId(assetId)
                .shotNumber(shotNumber)
                .startTime(stringValue(shot.get("startTime")))
                .endTime(stringValue(shot.get("endTime")))
                .durationSeconds(intValue(shot.get("durationSeconds"), null))
                .title(defaultString(shot.get("title"), "Storyboard Shot " + shotNumber))
                .purpose(stringValue(shot.get("purpose")))
                .shotType(stringValue(shot.get("shotType")))
                .cameraAngle(stringValue(shot.get("cameraAngle")))
                .cameraMovement(stringValue(shot.get("cameraMovement")))
                .lensSuggestion(stringValue(shot.get("lensSuggestion")))
                .fps(intValue(firstNonNull(shot.get("fps"), nested(shot, "cinematicExecution", "recommendedFPS")), null))
                .composition(stringValue(shot.get("composition")))
                .expression(valueMap(shot.get("expression")))
                .emotion(stringList(shot.get("emotion")))
                .bodyLanguage(valueMap(shot.get("bodyLanguage")))
                .lighting(stringValue(shot.get("lighting")))
                .environment(defaultString(firstNonNull(shot.get("environment"), shot.get("setDesign")), "Creator shooting space"))
                .action(stringValue(shot.get("action")))
                .voiceOver(stringValue(shot.get("voiceOver")))
                .dialogue(mapValue(shot.get("dialogue")))
                .textOverlay(stringValue(shot.get("textOverlay")))
                .transition(stringValue(shot.get("transition")))
                .soundDesign(stringList(shot.get("soundDesign")))
                .editingNotes(stringList(shot.get("editingNotes")))
                .retentionGoal(stringValue(shot.get("retentionGoal")))
                .creatorDirection(valueMap(shot.get("creatorDirection")))
                .subtitlePosition(stringValue(shot.get("subtitlePosition")))
                .mobileFocusArea(stringValue(shot.get("mobileFocusArea")))
                .safeZoneNotes(stringValue(shot.get("safeZoneNotes")))
                .executionDifficulty(mapValue(shot.get("executionDifficulty")))
                .cinematicExecution(mapValue(shot.get("cinematicExecution")))
                .rookieFriendlyGuide(mapValue(shot.get("rookieFriendlyGuide")))
                .sketchPrompt(prompt)
                .metadata(metadata)
                .build();
    }

    private CreatorStoryboardScene upsertScene(CreatorStoryboardScene scene) {
        UUID sceneId = jdbcTemplate.queryForObject(
                """
                insert into creator_storyboard_scenes (
                    id,
                    storyboard_id,
                    image_asset_id,
                    shot_number,
                    start_time,
                    end_time,
                    duration_seconds,
                    title,
                    purpose,
                    shot_type,
                    camera_angle,
                    camera_movement,
                    lens_suggestion,
                    fps,
                    composition,
                    expression,
                    emotion,
                    body_language,
                    lighting,
                    environment,
                    action,
                    voice_over,
                    dialogue,
                    text_overlay,
                    transition,
                    sound_design,
                    editing_notes,
                    retention_goal,
                    creator_direction,
                    subtitle_position,
                    mobile_focus_area,
                    safe_zone_notes,
                    execution_difficulty,
                    cinematic_execution,
                    rookie_friendly_guide,
                    sketch_prompt,
                    metadata,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, cast(? as jsonb), ?, ?, cast(? as jsonb), cast(? as jsonb), ?, cast(? as jsonb), ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, cast(? as jsonb), now(), now())
                on conflict (storyboard_id, shot_number) do update set
                    image_asset_id = coalesce(excluded.image_asset_id, creator_storyboard_scenes.image_asset_id),
                    start_time = excluded.start_time,
                    end_time = excluded.end_time,
                    duration_seconds = excluded.duration_seconds,
                    title = excluded.title,
                    purpose = excluded.purpose,
                    shot_type = excluded.shot_type,
                    camera_angle = excluded.camera_angle,
                    camera_movement = excluded.camera_movement,
                    lens_suggestion = excluded.lens_suggestion,
                    fps = excluded.fps,
                    composition = excluded.composition,
                    expression = excluded.expression,
                    emotion = excluded.emotion,
                    body_language = excluded.body_language,
                    lighting = excluded.lighting,
                    environment = excluded.environment,
                    action = excluded.action,
                    voice_over = excluded.voice_over,
                    dialogue = excluded.dialogue,
                    text_overlay = excluded.text_overlay,
                    transition = excluded.transition,
                    sound_design = excluded.sound_design,
                    editing_notes = excluded.editing_notes,
                    retention_goal = excluded.retention_goal,
                    creator_direction = excluded.creator_direction,
                    subtitle_position = excluded.subtitle_position,
                    mobile_focus_area = excluded.mobile_focus_area,
                    safe_zone_notes = excluded.safe_zone_notes,
                    execution_difficulty = excluded.execution_difficulty,
                    cinematic_execution = excluded.cinematic_execution,
                    rookie_friendly_guide = excluded.rookie_friendly_guide,
                    sketch_prompt = case
                        when excluded.sketch_prompt is null or excluded.sketch_prompt = '' then creator_storyboard_scenes.sketch_prompt
                        else excluded.sketch_prompt
                    end,
                    metadata = creator_storyboard_scenes.metadata || excluded.metadata,
                    updated_at = now()
                returning id
                """,
                UUID.class,
                UUID.randomUUID(),
                scene.getStoryboardId(),
                scene.getImageAssetId(),
                scene.getShotNumber(),
                scene.getStartTime(),
                scene.getEndTime(),
                scene.getDurationSeconds(),
                defaultString(scene.getTitle(), "Storyboard Shot " + defaultInt(scene.getShotNumber(), 1)),
                scene.getPurpose(),
                scene.getShotType(),
                scene.getCameraAngle(),
                scene.getCameraMovement(),
                scene.getLensSuggestion(),
                scene.getFps(),
                scene.getComposition(),
                toJson(scene.getExpression() == null ? Map.of() : scene.getExpression()),
                toJson(scene.getEmotion() == null ? List.of() : scene.getEmotion()),
                toJson(scene.getBodyLanguage() == null ? Map.of() : scene.getBodyLanguage()),
                scene.getLighting(),
                scene.getEnvironment(),
                scene.getAction(),
                scene.getVoiceOver(),
                toJson(scene.getDialogue() == null ? Map.of() : scene.getDialogue()),
                scene.getTextOverlay(),
                scene.getTransition(),
                toJson(scene.getSoundDesign() == null ? List.of() : scene.getSoundDesign()),
                toJson(scene.getEditingNotes() == null ? List.of() : scene.getEditingNotes()),
                scene.getRetentionGoal(),
                toJson(scene.getCreatorDirection() == null ? Map.of() : scene.getCreatorDirection()),
                scene.getSubtitlePosition(),
                scene.getMobileFocusArea(),
                scene.getSafeZoneNotes(),
                toJson(scene.getExecutionDifficulty() == null ? Map.of() : scene.getExecutionDifficulty()),
                toJson(scene.getCinematicExecution() == null ? Map.of() : scene.getCinematicExecution()),
                toJson(scene.getRookieFriendlyGuide() == null ? Map.of() : scene.getRookieFriendlyGuide()),
                scene.getSketchPrompt(),
                toJson(scene.getMetadata() == null ? Map.of() : scene.getMetadata())
        );
        return sceneRepository.findById(sceneId)
                .orElseThrow(() -> new IllegalStateException("Saved storyboard scene was not found: " + sceneId));
    }

    private StoryboardSceneResponse toResponse(
            CreatorStoryboardScene scene,
            CreatorAsset asset,
            String signedUrl,
            CreatorAsset lightingAsset,
            String lightingSignedUrl,
            CreatorAsset cameraPlanAsset,
            String cameraPlanSignedUrl,
            CreatorScriptShotPlan plan
    ) {
        Map<String, Object> metadata = scene.getMetadata() == null ? Map.of() : scene.getMetadata();
        String effectiveSignedUrl = defaultString(signedUrlFor(asset), signedUrl);
        String effectiveLightingSignedUrl = defaultString(signedUrlFor(lightingAsset), lightingSignedUrl);
        String effectiveCameraPlanSignedUrl = defaultString(signedUrlFor(cameraPlanAsset), cameraPlanSignedUrl);
        String responseImageKind = asset == null || asset.getMetadata() == null
                ? ""
                : assetKeyType(defaultString(stringValue(asset.getMetadata().get("imageKind")), asset.getAssetType()));
        CreatorAsset productionAsset = "production".equals(responseImageKind)
                ? asset
                : findAsset(uuidValue(metadata.get("productionImageAssetId")));
        return new StoryboardSceneResponse(
                scene.getId(),
                asset == null ? null : asset.getId(),
                scene.getShotNumber(),
                scene.getTitle(),
                scene.getStartTime(),
                scene.getEndTime(),
                scene.getDurationSeconds(),
                scene.getShotType(),
                scene.getCameraAngle(),
                scene.getCameraMovement(),
                scene.getLensSuggestion(),
                scene.getFps(),
                asset == null ? null : asset.getObjectKey(),
                effectiveSignedUrl,
                scene.getSketchPrompt(),
                productionAsset == null ? null : productionAsset.getId(),
                productionAsset == null ? null : productionAsset.getObjectKey(),
                signedUrlFor(productionAsset),
                defaultString(
                        stringValue(metadata.get("productionImagePrompt")),
                        productionAsset == null || productionAsset.getMetadata() == null
                                ? null
                                : stringValue(productionAsset.getMetadata().get("productionImagePrompt"))
                ),
                lightingAsset == null ? null : lightingAsset.getId(),
                lightingAsset == null ? null : lightingAsset.getObjectKey(),
                effectiveLightingSignedUrl,
                cameraPlanAsset == null ? null : cameraPlanAsset.getId(),
                cameraPlanAsset == null ? null : cameraPlanAsset.getObjectKey(),
                effectiveCameraPlanSignedUrl,
                stringValue(metadata.get("screenType")),
                intValue(metadata.get("renderWidth"), null),
                intValue(metadata.get("renderHeight"), null),
                plan == null ? mapValue(metadata.get("storyboardTag")) : plan.getStoryboardTag(),
                plan == null ? mapValue(metadata.get("lightingBuildSheetTag")) : plan.getLightingBuildSheetTag(),
                plan == null ? mapValue(metadata.get("cameraPlanSheetTag")) : plan.getCameraPlanSheetTag(),
                mapValue(metadata.get("rawShot"))
        );
    }

    private ShotImageUrlResponse toShotImageUrlResponse(CreatorScript script, Integer shotNumber, ShotImageAssets assets) {
        CreatorAsset source = firstAsset(assets.storyboard, assets.production, assets.lighting, assets.cameraPlan);
        Map<String, Object> metadata = source == null ? Map.of() : source.getMetadata();
        return new ShotImageUrlResponse(
                script.getId(),
                uuidValue(metadata.get("storyboardId")),
                script.getProjectId(),
                script.getStoryIdeaId(),
                shotNumber,
                assets.storyboard == null ? null : assets.storyboard.getId(),
                assets.storyboard == null ? null : assets.storyboard.getObjectKey(),
                signedUrlFor(assets.storyboard),
                assets.production == null ? null : assets.production.getId(),
                assets.production == null ? null : assets.production.getObjectKey(),
                signedUrlFor(assets.production),
                assets.production == null || assets.production.getMetadata() == null
                        ? null
                        : stringValue(assets.production.getMetadata().get("productionImagePrompt")),
                assets.lighting == null ? null : assets.lighting.getId(),
                assets.lighting == null ? null : assets.lighting.getObjectKey(),
                signedUrlFor(assets.lighting),
                assets.cameraPlan == null ? null : assets.cameraPlan.getId(),
                assets.cameraPlan == null ? null : assets.cameraPlan.getObjectKey(),
                signedUrlFor(assets.cameraPlan),
                defaultString(stringValue(metadata.get("screenType")), script.getScreenType()),
                intValue(metadata.get("renderWidth"), null),
                intValue(metadata.get("renderHeight"), null),
                maxCreatedAt(assets.storyboard, assets.production, assets.lighting, assets.cameraPlan)
        );
    }

    private CreatorAsset firstAsset(CreatorAsset... assets) {
        for (CreatorAsset asset : assets) {
            if (asset != null) {
                return asset;
            }
        }
        return null;
    }

    private OffsetDateTime maxCreatedAt(CreatorAsset... assets) {
        OffsetDateTime latest = null;
        for (CreatorAsset asset : assets) {
            if (asset == null || asset.getCreatedAt() == null) {
                continue;
            }
            if (latest == null || asset.getCreatedAt().isAfter(latest)) {
                latest = asset.getCreatedAt();
            }
        }
        return latest;
    }

    private String signedUrlFor(CreatorAsset asset) {
        if (asset == null) {
            return null;
        }
        if (asset.getBucket() == null || asset.getBucket().isBlank() || asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
            return asset.getPublicUrl();
        }
        try {
            Integer ttlSeconds = asset.getMetadata() == null ? null : intValue(asset.getMetadata().get("signedUrlTtlSeconds"), null);
            return assetStorageService.signedUrl(
                    asset.getBucket(),
                    asset.getObjectKey(),
                    signedUrlTtl(ttlSeconds == null ? null : ttlSeconds.longValue())
            );
        } catch (RuntimeException ex) {
            return asset.getPublicUrl();
        }
    }

    private Map<Integer, CreatorScriptShotPlan> loadPlanByShotNumber(UUID scriptId) {
        Map<Integer, CreatorScriptShotPlan> planByShotNumber = new LinkedHashMap<>();
        for (CreatorScriptShotPlan plan : shotPlanRepository.findByScriptIdOrderByShotNumberAsc(scriptId)) {
            if (plan.getShotNumber() != null) {
                planByShotNumber.put(plan.getShotNumber(), plan);
            }
        }
        return planByShotNumber;
    }

    private Map<Integer, String> loadShotIdByNumber(UUID scriptId) {
        Map<Integer, String> shotIdByNumber = new LinkedHashMap<>();
        for (CreatorScriptShot shot : scriptShotRepository.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(scriptId)) {
            if (shot.getShotNumber() != null && shot.getId() != null) {
                shotIdByNumber.put(shot.getShotNumber(), shot.getId().toString());
            }
        }
        return shotIdByNumber;
    }

    private void replaceSceneResponse(List<StoryboardSceneResponse> scenes, StoryboardSceneResponse replacement) {
        if (replacement == null || scenes == null) {
            return;
        }
        for (int index = 0; index < scenes.size(); index++) {
            StoryboardSceneResponse current = scenes.get(index);
            if (current != null && replacement.shotNumber() != null && replacement.shotNumber().equals(current.shotNumber())) {
                scenes.set(index, replacement);
                return;
            }
        }
        scenes.add(replacement);
    }

    private int progressFor(int shotIndex, int totalShots, int assetStep) {
        int totalAssets = Math.max(1, totalShots * 3);
        int completedAssets = Math.min(totalAssets, (shotIndex * 3) + assetStep);
        return 8 + (int) Math.round((completedAssets * 86.0d) / totalAssets);
    }

    private void publishStoryboardProgress(
            UUID generationJobId,
            CreatorStoryboard storyboard,
            CreatorScript script,
            String screenType,
            RenderSize renderSize,
            List<StoryboardSceneResponse> sceneResponses,
            int progress,
            String message
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        StoryboardResponse partial = new StoryboardResponse(
                storyboard.getId(),
                script.getId(),
                storyboard.getProjectId(),
                storyboard.getIdeaId(),
                storyboard.getTitle(),
                screenType,
                renderSize.width(),
                renderSize.height(),
                storyboard.getDurationSeconds(),
                storyboard.getTotalShots(),
                "RUNNING",
                sceneResponses == null ? List.of() : List.copyOf(sceneResponses),
                storyboard.getCreatedAt()
        );
        output.put("storyboardId", storyboard.getId().toString());
        output.put("scriptId", script.getId().toString());
        output.put("screenplayId", script.getId().toString());
        output.put("screenType", screenType);
        output.put("renderWidth", renderSize.width());
        output.put("renderHeight", renderSize.height());
        output.put("sceneCount", partial.scenes().size());
        output.put("storyboard", toMap(partial));
        output.put("steps", storyboardGenerationSteps(progress, message, sceneResponses, storyboard.getTotalShots()));
        generationJobService.updateGenerationJobProgress(generationJobId, progress, message, output);
    }

    private List<Map<String, Object>> storyboardGenerationSteps(
            int progress,
            String message,
            List<StoryboardSceneResponse> sceneResponses,
            int totalShots
    ) {
        List<StoryboardSceneResponse> scenes = sceneResponses == null ? List.of() : sceneResponses;
        int expected = Math.max(0, totalShots);
        int storyboardReady = scenes.size();
        int lightingReady = (int) scenes.stream()
                .filter(scene -> scene != null && scene.lightingImageUrl() != null && !scene.lightingImageUrl().isBlank())
                .count();
        int cameraReady = (int) scenes.stream()
                .filter(scene -> scene != null && scene.cameraPlanImageUrl() != null && !scene.cameraPlanImageUrl().isBlank())
                .count();
        String lowerMessage = defaultString(message, "").toLowerCase(Locale.ROOT);
        return List.of(
                generationStep("Create storyboard pack", progress > 8 || storyboardReady > 0, lowerMessage.contains("pack")),
                generationStep("Render storyboard images", expected > 0 && storyboardReady >= expected, lowerMessage.contains("storyboard image"), storyboardReady, expected),
                generationStep("Render lighting sheets", expected > 0 && lightingReady >= expected, lowerMessage.contains("lighting"), lightingReady, expected),
                generationStep("Render DP camera sheets", progress >= 100 || (expected > 0 && cameraReady >= expected), lowerMessage.contains("dp camera") || lowerMessage.contains("camera plan"), cameraReady, expected)
        );
    }

    private Map<String, Object> generationStep(String label, boolean completed, boolean running) {
        return generationStep(label, completed, running, null, null);
    }

    private Map<String, Object> generationStep(String label, boolean completed, boolean running, Integer completedCount, Integer totalCount) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("label", label);
        step.put("status", completed ? "completed" : running ? "running" : "pending");
        if (completedCount != null && totalCount != null && totalCount > 0) {
            step.put("detail", Math.min(completedCount, totalCount) + "/" + totalCount + " shots");
        }
        return step;
    }

    private GeneratedAsset generateStoryboardAsset(
            CreatorScript script,
            UUID storyboardId,
            Map<String, Object> shot,
            int shotNumber,
            String shotId,
            String screenType,
            RenderSize renderSize,
            Duration signedUrlTtl,
            String prompt,
            GenerateStoryboardRequest request
    ) {
        List<String> storyboardReferenceUrls = new ArrayList<>(storyboardReferenceImageUrls(script, shot));
        List<Map<String, Object>> storyboardReferenceAssets = storyboardReferenceImageAssets(script, shot);
        if (request != null && request.productReferenceImageUrls() != null) {
            request.productReferenceImageUrls().stream()
                    .filter(url -> url != null && !url.isBlank())
                    .map(String::trim)
                    .filter(url -> !storyboardReferenceUrls.contains(url))
                    .limit(Math.max(0, 9 - storyboardReferenceUrls.size()))
                    .forEach(storyboardReferenceUrls::add);
        }
        GeneratedStoryboardImage generatedImage = generateStoryboardImage(
                shot,
                screenType,
                renderSize,
                prompt,
                downloadStoryboardReferenceImages(storyboardReferenceUrls, storyboardReferenceAssets),
                storyboardReferenceUrls
        );
        String objectKey = objectKey(script, storyboardId, shotId, "storyboard");
        AssetStorageService.StoredObject storedObject = assetStorageService.uploadCreatorAsset(
                objectKey,
                generatedImage.bytes(),
                CONTENT_TYPE_JPEG,
                signedUrlTtl
        );
        CreatorAsset asset = upsertAsset(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .storyboardId(storyboardId)
                .assetType(ASSET_TYPE_STORYBOARD_IMAGE)
                .bucket(storedObject.bucket())
                .objectKey(storedObject.objectKey())
                .contentType(storedObject.contentType())
                .sizeBytes(storedObject.sizeBytes())
                .publicUrl(storedObject.signedUrl())
                .metadata(assetMetadata(script, storyboardId, shotId, shotNumber, "storyboard", screenType, renderSize, signedUrlTtl, generatedImage.metadata()))
                .build());
        return new GeneratedAsset(asset, storedObject.signedUrl());
    }

    private GeneratedAsset generateProductionImageAsset(
            CreatorScript script,
            UUID storyboardId,
            Map<String, Object> shot,
            int shotNumber,
            String shotId,
            String screenType,
            RenderSize renderSize,
            Duration signedUrlTtl,
            String prompt,
            GenerateStoryboardRequest request
    ) {
        if (!properties.getAi().isStoryboardImageGenerationEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini image generation must be enabled to create a production video image anchor.");
        }
        List<String> referenceUrls = new ArrayList<>(storyboardReferenceImageUrls(script, shot));
        List<Map<String, Object>> referenceAssets = storyboardReferenceImageAssets(script, shot);
        if (request != null && request.productReferenceImageUrls() != null) {
            request.productReferenceImageUrls().stream()
                    .filter(url -> url != null && !url.isBlank())
                    .map(String::trim)
                    .filter(url -> !referenceUrls.contains(url))
                    .limit(Math.max(0, 9 - referenceUrls.size()))
                    .forEach(referenceUrls::add);
        }
        GeneratedStoryboardImage generatedImage = generateStoryboardImage(
                shot,
                screenType,
                renderSize,
                prompt,
                downloadStoryboardReferenceImages(referenceUrls, referenceAssets),
                referenceUrls
        );
        String objectKey = objectKey(script, storyboardId, shotId, "production");
        AssetStorageService.StoredObject storedObject = assetStorageService.uploadCreatorAsset(
                objectKey,
                generatedImage.bytes(),
                CONTENT_TYPE_JPEG,
                signedUrlTtl
        );
        Map<String, Object> metadata = assetMetadata(script, storyboardId, shotId, shotNumber, "production", screenType, renderSize, signedUrlTtl, generatedImage.metadata());
        metadata.put("imageKind", "production");
        metadata.put("referenceRole", "generated_product_scene_frame");
        metadata.put("sourceProductReferenceCount", referenceUrls.size());
        metadata.put("productionImagePrompt", prompt);
        CreatorAsset asset = upsertAsset(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .storyboardId(storyboardId)
                .assetType(ASSET_TYPE_PRODUCTION_IMAGE_ANCHOR)
                .bucket(storedObject.bucket())
                .objectKey(storedObject.objectKey())
                .contentType(storedObject.contentType())
                .sizeBytes(storedObject.sizeBytes())
                .publicUrl(storedObject.signedUrl())
                .metadata(metadata)
                .build());
        return new GeneratedAsset(asset, storedObject.signedUrl());
    }

    private GeneratedAsset generateSheetAsset(
            CreatorScript script,
            UUID storyboardId,
            Map<String, Object> shot,
            int shotNumber,
            String shotId,
            String screenType,
            RenderSize renderSize,
            Duration signedUrlTtl,
            String imageKind,
            String assetType,
            Map<String, Object> tag
    ) {
        String prompt = buildProductionSheetPrompt(imageKind, shot, script.getScriptPayload(), tag, screenType, renderSize);
        GeneratedStoryboardImage generatedImage = generateStoryboardImage(shot, screenType, renderSize, prompt);
        String objectKey = objectKey(script, storyboardId, shotId, imageKind);
        AssetStorageService.StoredObject storedObject = assetStorageService.uploadCreatorAsset(
                objectKey,
                generatedImage.bytes(),
                CONTENT_TYPE_JPEG,
                signedUrlTtl
        );
        Map<String, Object> metadata = assetMetadata(script, storyboardId, shotId, shotNumber, imageKind, screenType, renderSize, signedUrlTtl, generatedImage.metadata());
        metadata.put("imageKind", imageKind);
        metadata.put("sourceTag", tag == null ? Map.of() : tag);
        CreatorAsset asset = upsertAsset(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .storyboardId(storyboardId)
                .assetType(assetType)
                .bucket(storedObject.bucket())
                .objectKey(storedObject.objectKey())
                .contentType(storedObject.contentType())
                .sizeBytes(storedObject.sizeBytes())
                .publicUrl(storedObject.signedUrl())
                .metadata(metadata)
                .build());
        return new GeneratedAsset(asset, storedObject.signedUrl());
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
                toJson(asset.getMetadata() == null ? Map.of() : asset.getMetadata())
        );
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("Saved creator asset was not found: " + assetId));
    }
    private GeneratedStoryboardImage generateStoryboardImage(Map<String, Object> shot, String screenType, RenderSize size, String prompt) {
        return generateStoryboardImage(shot, screenType, size, prompt, List.of(), List.of());
    }

    private GeneratedStoryboardImage generateStoryboardImage(
            Map<String, Object> shot,
            String screenType,
            RenderSize size,
            String prompt,
            List<StoryboardImageGenerationService.ReferenceImageInput> referenceImages,
            List<String> referenceImageUrls
    ) {
        if (!properties.getAi().isStoryboardImageGenerationEnabled()) {
            return new GeneratedStoryboardImage(
                    renderStoryboardImage(shot, screenType, size, prompt),
                    Map.of(
                            "provider", "local",
                            "model", "local_storyboard_sketch_v1",
                            "renderer", "local_storyboard_sketch_v1"
                    )
            );
        }
        String imagePrompt = appendShotReferenceUsageGuidance(prompt, shot);
        imagePrompt = appendStoryboardReferenceGuidance(imagePrompt, referenceImageUrls);

        StoryboardImageGenerationService.GeneratedImage generatedImage =
                referenceImages == null || referenceImages.isEmpty()
                        ? storyboardImageGenerationService.generateStoryboardImage(imagePrompt, screenType)
                        : storyboardImageGenerationService.generateImageFromReferences(imagePrompt, referenceImages, screenType);
        Map<String, Object> metadata = new LinkedHashMap<>(generatedImage.metadata() == null ? Map.of() : generatedImage.metadata());
        if (referenceImageUrls != null && !referenceImageUrls.isEmpty()) {
            metadata.put("referenceImageMode", "product_brand_reference_for_storyboard_sketch");
            metadata.put("referenceImageUrls", referenceImageUrls);
            metadata.put("referenceImageUsed", referenceImages != null && !referenceImages.isEmpty());
            metadata.put("referenceImageCount", referenceImages == null ? 0 : referenceImages.size());
        }
        return new GeneratedStoryboardImage(
                normalizeToRenderSizeJpeg(generatedImage.bytes(), size),
                metadata
        );
    }

    private List<String> storyboardReferenceImageUrls(CreatorScript script) {
        return script == null ? List.of() : storyboardReferenceImageUrls(script.getScriptPayload());
    }

    private List<String> storyboardReferenceImageUrls(CreatorScript script, Map<String, Object> shot) {
        List<String> urls = new ArrayList<>(storyboardReferenceImageUrls(script));
        Map<String, Object> safeShot = shot == null ? Map.of() : shot;
        addStoryboardReferenceUrls(urls, safeShot.get("visualReferenceImageUrls"));
        addStoryboardReferenceUrls(urls, safeShot.get("visualReferenceImages"));
        return urls.stream().limit(8).toList();
    }

    private List<String> storyboardReferenceImageUrls(Map<String, Object> screenplay) {
        Map<String, Object> safeScreenplay = screenplay == null ? Map.of() : screenplay;
        Map<String, Object> creatorContext = mapValue(safeScreenplay.get("creatorContext"));
        Map<String, Object> metadata = mapValue(creatorContext.get("metadata"));
        Map<String, Object> productBrief = mapValue(creatorContext.get("productIntelligenceBrief"));
        if (productBrief.isEmpty()) {
            productBrief = mapValue(safeScreenplay.get("productIntelligenceBrief"));
        }
        Map<String, Object> productUnderstanding = mapValue(productBrief.get("productUnderstanding"));
        List<String> urls = new ArrayList<>();
        addStoryboardReferenceUrls(urls, metadata.get("referenceImageUrls"));
        addStoryboardReferenceUrls(urls, creatorContext.get("referenceImageUrls"));
        addStoryboardReferenceUrls(urls, productBrief.get("referenceImageUrls"));
        addStoryboardReferenceUrls(urls, productBrief.get("imageUrls"));
        addStoryboardReferenceUrls(urls, productBrief.get("productImageUrls"));
        addStoryboardReferenceUrls(urls, productUnderstanding.get("imageUrls"));
        addDirectProductReferenceImageUrl(urls, productBrief.get("sourceUrl"));
        addDirectProductReferenceImageUrl(urls, productUnderstanding.get("sourceUrl"));
        addStoryboardReferenceUrls(urls, safeScreenplay.get("referenceImageUrls"));
        addStoryboardReferenceUrls(urls, safeScreenplay.get("productImageUrls"));
        Map<String, Object> productIntelligence = mapValue(safeScreenplay.get("productIntelligence"));
        addStoryboardReferenceUrls(urls, productIntelligence.get("sourceImages"));
        addStoryboardReferenceUrls(urls, productIntelligence.get("imageUrls"));
        addStoryboardReferenceUrls(urls, safeScreenplay.get("generatedAssets"));
        return urls.stream().limit(4).toList();
    }

    private void addStoryboardReferenceUrls(List<String> urls, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> addStoryboardReferenceUrls(urls, item));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            addStoryboardReferenceUrls(urls, map.get("publicUrl"));
            addStoryboardReferenceUrls(urls, map.get("signedUrl"));
            addStoryboardReferenceUrls(urls, map.get("assetUrl"));
            addStoryboardReferenceUrls(urls, map.get("imageUrl"));
            addStoryboardReferenceUrls(urls, map.get("url"));
            return;
        }
        String url = stringValue(value).trim();
        if ((url.startsWith("https://") || url.startsWith("http://")) && !urls.contains(url)) {
            urls.add(url);
        }
    }

    private void addDirectProductReferenceImageUrl(List<String> urls, Object value) {
        String url = stringValue(value).trim();
        if (isProductReferenceImageUrl(url) && !urls.contains(url)) {
            urls.add(url);
        }
    }

    private boolean isProductReferenceImageUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            String path = URI.create(value).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).matches(".*\\.(avif|gif|jpe?g|png|webp)$");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private List<Map<String, Object>> storyboardReferenceImageAssets(
            CreatorScript script,
            Map<String, Object> shot
    ) {
        if (script == null || shot == null) return List.of();
        List<Map<String, Object>> assets = new ArrayList<>();
        addStoryboardReferenceAssets(assets, shot.get("visualReferenceImages"));
        return assets.stream().limit(8).toList();
    }

    private void addStoryboardReferenceAssets(List<Map<String, Object>> assets, Object value) {
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> asset = new LinkedHashMap<>();
            map.forEach((key, fieldValue) -> asset.put(String.valueOf(key), fieldValue));
            String bucket = stringValue(asset.get("bucket"));
            String objectKey = stringValue(asset.get("objectKey"));
            if (!bucket.equals(assetStorageService.creatorAssetsBucket()) || objectKey.isBlank()) continue;
            if (assets.stream().noneMatch(existing ->
                    objectKey.equals(stringValue(existing.get("objectKey"))))) {
                assets.add(asset);
            }
            if (assets.size() >= 8) return;
        }
    }

    private List<StoryboardImageGenerationService.ReferenceImageInput> downloadStoryboardReferenceImages(List<String> urls) {
        return downloadStoryboardReferenceImages(urls, List.of());
    }

    private List<StoryboardImageGenerationService.ReferenceImageInput> downloadStoryboardReferenceImages(
            List<String> urls,
            List<Map<String, Object>> managedAssets
    ) {
        List<StoryboardImageGenerationService.ReferenceImageInput> references = new ArrayList<>();
        List<String> managedUrls = new ArrayList<>();
        for (Map<String, Object> asset : managedAssets == null ? List.<Map<String, Object>>of() : managedAssets) {
            if (references.size() >= 8) break;
            String objectKey = stringValue(asset.get("objectKey"));
            try (AssetStorageService.StreamedObject stored = assetStorageService.openObjectStream(
                    stringValue(asset.get("bucket")),
                    objectKey
            )) {
                if (stored.sizeBytes() > 15L * 1024L * 1024L) continue;
                byte[] bytes = stored.inputStream().readNBytes(15 * 1024 * 1024 + 1);
                if (bytes.length == 0 || bytes.length > 15 * 1024 * 1024) continue;
                String contentType = defaultString(
                        stringValue(asset.get("contentType")),
                        defaultString(stored.contentType(), "image/png")
                ).toLowerCase(Locale.ROOT);
                if (!List.of("image/jpeg", "image/jpg", "image/png", "image/webp").contains(contentType)) continue;
                if ("image/jpg".equals(contentType)) contentType = "image/jpeg";
                String usageMode = stringValue(firstNonNull(
                        asset.get("visualReferenceUsageMode"),
                        asset.get("usageMode")
                ));
                references.add(new StoryboardImageGenerationService.ReferenceImageInput(
                        bytes,
                        contentType,
                        ("EXACT_SOURCE".equalsIgnoreCase(usageMode) ? "exact_visual_source_" : "visual_inspiration_only_") + references.size()
                ));
                addStoryboardReferenceUrls(managedUrls, asset.get("signedUrl"));
                addStoryboardReferenceUrls(managedUrls, asset.get("publicUrl"));
                addStoryboardReferenceUrls(managedUrls, asset.get("assetUrl"));
            } catch (IOException | RuntimeException ex) {
                log.warn(
                        "Storyboard managed reference image download skipped objectKeySuffix={} errorType={}",
                        objectKey.contains("/") ? objectKey.substring(objectKey.lastIndexOf('/') + 1) : objectKey,
                        ex.getClass().getSimpleName()
                );
            }
        }
        for (String url : urls == null ? List.<String>of() : urls.stream().limit(8).toList()) {
            if (references.size() >= 8) break;
            if (managedUrls.contains(url)) continue;
            try {
                ResponseEntity<byte[]> response = webClient
                        .get()
                        .uri(URI.create(url))
                        .retrieve()
                        .toEntity(byte[].class)
                        .block(Duration.ofSeconds(12));
                if (response == null || response.getBody() == null || response.getBody().length == 0) {
                    continue;
                }
                String contentType = response.getHeaders().getContentType() == null
                        ? "image/png"
                        : response.getHeaders().getContentType().toString();
                if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                    continue;
                }
                references.add(new StoryboardImageGenerationService.ReferenceImageInput(
                        response.getBody(),
                        contentType,
                        "external_reference_" + (references.size() + 1)
                ));
            } catch (RuntimeException ex) {
                log.warn(
                        "Storyboard reference image download skipped url={} errorType={} errorMessage={}",
                        url,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
            }
        }
        return references;
    }

    private String appendStoryboardReferenceGuidance(String prompt, Map<String, Object> screenplay) {
        Map<String, Object> safeScreenplay = screenplay == null ? Map.of() : screenplay;
        return appendStoryboardReferenceGuidance(prompt, storyboardReferenceImageUrls(safeScreenplay), storyboardReferenceDetails(safeScreenplay));
    }

    private String appendStoryboardReferenceGuidance(String prompt, List<String> referenceImageUrls) {
        return appendStoryboardReferenceGuidance(prompt, referenceImageUrls, "");
    }

    private String appendShotReferenceUsageGuidance(String prompt, Map<String, Object> shot) {
        List<Map<String, Object>> references = new ArrayList<>();
        Object rawReferences = shot == null ? null : shot.get("visualReferenceImages");
        if (rawReferences instanceof List<?> values) {
            values.stream().map(this::mapValue).filter(value -> !value.isEmpty()).forEach(references::add);
        }
        if (references.isEmpty()) return prompt;
        boolean hasExactSource = references.stream().anyMatch(reference ->
                "EXACT_SOURCE".equalsIgnoreCase(stringValue(firstNonNull(
                        reference.get("visualReferenceUsageMode"),
                        reference.get("usageMode")
                )))
        );
        if (hasExactSource) {
            return prompt + """

                    [CLIENT REFERENCE INTENT: USE EXACTLY]
                    - References marked exact_visual_source are approved visual source-of-truth for this shot.
                    - Preserve their visible product identity, product name, logo, packaging design, label copy, claims, colors, proportions, composition, and distinctive details exactly.
                    - Do not replace those exact-source details with product information inferred from another reference.
                    - For a storyboard output, translate only the rendering medium while keeping the exact source identity and composition faithful. For a production frame, keep the exact visual anchor faithful.
                    """;
        }
        return prompt + """

                [CLIENT REFERENCE INTENT: INSPIRATION ONLY]
                - Use client images only for mood, composition, lighting, texture, palette, and pacing.
                - Never copy their name, logo, packaging, claims, trademarks, label layout, artwork, or product identity.
                - The approved project concept and product data remain the only source-of-truth.
                """;
    }

    private String appendStoryboardReferenceGuidance(String prompt, List<String> referenceImageUrls, String referenceDetails) {
        if (referenceImageUrls == null || referenceImageUrls.isEmpty()) {
            return prompt;
        }
        String detailsLine = referenceDetails == null || referenceDetails.isBlank()
                ? ""
                : "- User reference notes to preserve in screenplay/storyboard planning: " + truncatePromptText(referenceDetails, 420) + "\n";
        return prompt + """

                [PRODUCT / BRAND REFERENCE IMAGE GUIDANCE]
                - Canonical product images, when explicitly supplied by this project, may be used to keep the approved project product consistent.
                - Client-review images explicitly marked INSPIRATION_ONLY may contribute only broad mood, composition, lighting, texture, palette, and pacing cues.
                - Client-review images explicitly marked EXACT_SOURCE are approved visual source-of-truth and must retain their visible product and composition details faithfully.
                - Never copy an INSPIRATION_ONLY image's product name, logo, packaging text, claims, trademark, distinctive artwork, exact label layout, or protected identity.
                - Preserve the core essence and narrative of this project's approved ad concept.
                - Unless EXACT_SOURCE is explicitly selected, product name, logo, pack design, visible copy, claims, ingredients, and all other product details must come only from this project's screenplay and approved product data.
                - If an inspiration image conflicts with approved project data, ignore the inspiration image.
                %s\
                - Translate the approved project idea into the requested storyboard or production-frame format while honoring the explicit reference intent.
                - This is still a client-review planning sketch, not a final advertisement frame. The supplied product images remain separate product visual anchors for later image-led video generation.
                """.formatted(detailsLine);
    }

    private String storyboardReferenceDetails(Map<String, Object> screenplay) {
        Map<String, Object> safeScreenplay = screenplay == null ? Map.of() : screenplay;
        Map<String, Object> creatorContext = mapValue(safeScreenplay.get("creatorContext"));
        Map<String, Object> metadata = mapValue(creatorContext.get("metadata"));
        return defaultString(firstNonNull(
                firstNonNull(safeScreenplay.get("referenceImageDetails"), safeScreenplay.get("screenplayEnhancementReferenceDetails")),
                firstNonNull(creatorContext.get("referenceImageDetails"), metadata.get("referenceImageDetails"))
        ), "");
    }

    private byte[] normalizeToRenderSizeJpeg(byte[] sourceBytes, RenderSize size) {
        try {
            BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(sourceBytes));
            if (source == null) {
                throw new IllegalStateException("Generated storyboard image could not be decoded.");
            }
            BufferedImage target = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(new Color(242, 240, 232));
                g.fillRect(0, 0, size.width(), size.height());
                g.drawImage(source, 0, 0, size.width(), size.height(), null);
            } finally {
                g.dispose();
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ImageIO.write(target, "jpg", out);
                return out.toByteArray();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to normalize generated storyboard image", ex);
        }
    }

    private byte[] renderStoryboardImage(Map<String, Object> shot, String screenType, RenderSize size, String prompt) {
        BufferedImage image = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color paper = new Color(242, 240, 232);
            Color ink = new Color(36, 36, 34);
            Color wash = new Color(222, 220, 212);
            g.setColor(paper);
            g.fillRect(0, 0, size.width(), size.height());

            int margin = Math.max(42, size.width() / 28);
            int topHeight = "horizontal".equals(screenType) ? 110 : 145;
            int bottomHeight = "horizontal".equals(screenType) ? 235 : 360;
            int sketchX = margin;
            int sketchY = topHeight;
            int sketchW = size.width() - (margin * 2);
            int sketchH = size.height() - topHeight - bottomHeight - margin;

            drawOuterFrame(g, ink, margin, size);
            drawHeader(g, ink, shot, size, margin, topHeight);
            drawSketchArea(g, ink, wash, shot, screenType, sketchX, sketchY, sketchW, sketchH);
            drawBottomNotes(g, ink, shot, size, margin, sketchY + sketchH + 22);
            drawFooterPromptMark(g, ink, prompt, size, margin);
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to render storyboard image", ex);
        }
    }

    private void drawOuterFrame(Graphics2D g, Color ink, int margin, RenderSize size) {
        g.setColor(ink);
        g.setStroke(new BasicStroke(4f));
        g.drawRect(margin / 2, margin / 2, size.width() - margin, size.height() - margin);
        g.setStroke(new BasicStroke(1.4f));
        for (int i = 0; i < 11; i++) {
            int x = margin + (i * 97) % (size.width() - margin * 2);
            int y = margin + (i * 151) % (size.height() - margin * 2);
            g.drawLine(x, y, Math.min(size.width() - margin, x + 60), Math.min(size.height() - margin, y + 12));
        }
    }

    private void drawHeader(Graphics2D g, Color ink, Map<String, Object> shot, RenderSize size, int margin, int topHeight) {
        int shotNumber = intValue(shot.get("shotNumber"), 1);
        String title = uppercase(defaultString(shot.get("title"), "Storyboard Shot"));
        String timestamp = defaultString(shot.get("startTime"), "0:00") + " - " + defaultString(shot.get("endTime"), "0:00");
        g.setColor(ink);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size.width() > size.height() ? 30 : 38));
        g.drawString("SHOT " + "%02d".formatted(shotNumber), margin, margin + 42);
        drawCentered(g, title, size.width() / 2, margin + 42, size.width() - margin * 6);
        g.drawString(timestamp, size.width() - margin - textWidth(g, timestamp), margin + 42);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(margin, topHeight - 25, size.width() - margin, topHeight - 25);
    }

    private void drawSketchArea(
            Graphics2D g,
            Color ink,
            Color wash,
            Map<String, Object> shot,
            String screenType,
            int x,
            int y,
            int width,
            int height
    ) {
        g.setColor(new Color(236, 234, 226));
        g.fillRect(x, y, width, height);
        g.setColor(ink);
        g.setStroke(new BasicStroke(3f));
        g.drawRect(x, y, width, height);

        int frameW;
        int frameH;
        if ("horizontal".equals(screenType)) {
            frameW = Math.min(width - 120, (int) (height * 1.777));
            frameH = (int) (frameW / 1.777);
            if (frameH > height - 110) {
                frameH = height - 110;
                frameW = (int) (frameH * 1.777);
            }
        } else {
            frameH = height - 90;
            frameW = (int) (frameH * 0.5625);
            if (frameW > width - 120) {
                frameW = width - 120;
                frameH = (int) (frameW / 0.5625);
            }
        }
        int frameX = x + (width - frameW) / 2;
        int frameY = y + 44;
        g.setColor(new Color(248, 247, 241));
        g.fillRect(frameX, frameY, frameW, frameH);
        g.setColor(ink);
        g.setStroke(new BasicStroke(2.4f));
        g.drawRect(frameX, frameY, frameW, frameH);

        drawSceneSketch(g, ink, wash, shot, frameX, frameY, frameW, frameH);
        drawInsideFrameText(g, ink, shot, screenType, frameX, frameY, frameW, frameH);
    }

    private void drawSceneSketch(Graphics2D g, Color ink, Color wash, Map<String, Object> shot, int x, int y, int width, int height) {
        String shotType = lower(shot.get("shotType"));
        boolean close = shotType.contains("close");
        int horizon = y + (int) (height * 0.62);
        g.setColor(wash);
        g.fillRect(x + 16, horizon, width - 32, height - (horizon - y) - 18);
        g.setColor(ink);
        g.setStroke(new BasicStroke(1.6f));
        g.drawLine(x + 20, horizon, x + width - 20, horizon);

        int subjectX = x + (int) (width * 0.48);
        int subjectY = y + (int) (height * (close ? 0.48 : 0.55));
        int head = Math.max(42, width / (close ? 6 : 10));
        g.setStroke(new BasicStroke(3f));
        g.drawOval(subjectX - head / 2, subjectY - head, head, head);
        g.drawLine(subjectX, subjectY, subjectX, subjectY + head * 2);
        g.drawLine(subjectX, subjectY + head / 2, subjectX - head, subjectY + head);
        g.drawLine(subjectX, subjectY + head / 2, subjectX + head, subjectY + head);
        g.drawLine(subjectX, subjectY + head * 2, subjectX - head / 2, subjectY + head * 3);
        g.drawLine(subjectX, subjectY + head * 2, subjectX + head / 2, subjectY + head * 3);
        g.setStroke(new BasicStroke(1.4f));
        g.drawLine(subjectX - head / 5, subjectY - head / 2, subjectX - head / 10, subjectY - head / 2);
        g.drawLine(subjectX + head / 10, subjectY - head / 2, subjectX + head / 5, subjectY - head / 2);
        g.drawArc(subjectX - head / 5, subjectY - head / 3, head / 2, head / 3, 200, 140);

        int propX = x + width / 10;
        int propY = horizon - height / 8;
        g.setStroke(new BasicStroke(2f));
        g.drawRect(propX, propY, width / 5, height / 8);
        g.drawLine(propX + 12, propY + 12, propX + width / 5 - 12, propY + height / 8 - 12);
        g.drawLine(x + width - width / 5, y + height / 5, x + width - 40, y + height / 5);
        g.drawLine(x + width - width / 5, y + height / 5, x + width - width / 5, y + height / 5 + 90);

        String movement = upper(defaultString(firstNonNull(shot.get("cameraMovement"), nested(shot, "cinematicExecution", "cameraStyle")), "STATIC"));
        if (!movement.contains("STATIC")) {
            drawArrow(g, x + width / 2, y + height / 5, x + width / 2, y + height / 5 + height / 6);
        }
        drawArrow(g, subjectX + head, subjectY - head / 2, subjectX + head * 2, subjectY - head / 2);
    }

    private void drawInsideFrameText(Graphics2D g, Color ink, Map<String, Object> shot, String screenType, int x, int y, int width, int height) {
        g.setColor(ink);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(18, width / 34)));
        String overlay = stringValue(shot.get("textOverlay"));
        if (!overlay.isBlank()) {
            drawCentered(g, uppercase(overlay), x + width / 2, y + 42, width - 50);
        }

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(17, width / 38)));
        int infoY = y + 76;
        drawLabel(g, "FRAME", "horizontal".equals(screenType) ? "16:9 HORIZONTAL" : "9:16 VERTICAL", x + 18, infoY);
        drawLabel(g, "CAM", defaultString(shot.get("cameraAngle"), "PLANNED ANGLE"), x + 18, infoY + 30);
        drawLabel(g, "SHOT", defaultString(shot.get("shotType"), "STORYBOARD"), x + 18, infoY + 60);

        String dialogue = dialogueLine(shot);
        if (!dialogue.isBlank()) {
            int boxH = Math.max(52, height / 10);
            int boxY = y + height - boxH - 28;
            g.setColor(new Color(255, 255, 255));
            g.fillRect(x + 32, boxY, width - 64, boxH);
            g.setColor(ink);
            g.drawRect(x + 32, boxY, width - 64, boxH);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(18, width / 34)));
            drawWrappedText(g, dialogue, x + 48, boxY + 30, width - 96, Math.max(24, width / 28), 2);
        }

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(16, width / 44)));
        String action = defaultString(shot.get("action"), defaultString(shot.get("primaryActorAction"), "Perform the planned action naturally."));
        drawWrappedText(g, "ACTION: " + action, x + 18, y + height - 118, width - 36, Math.max(20, width / 38), 3);
    }

    private void drawBottomNotes(Graphics2D g, Color ink, Map<String, Object> shot, RenderSize size, int margin, int startY) {
        int columnGap = 34;
        int columnW = (size.width() - margin * 2 - columnGap) / 2;
        int line = size.width() > size.height() ? 25 : 32;
        g.setColor(ink);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size.width() > size.height() ? 24 : 30));
        g.drawString("CAMERA", margin, startY);
        g.drawString("PERFORMANCE", margin + columnW + columnGap, startY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size.width() > size.height() ? 19 : 24));

        List<String> cameraLines = List.of(
                "CAM: " + defaultString(shot.get("cameraAngle"), "planned angle"),
                "SHOT: " + defaultString(shot.get("shotType"), "storyboard shot"),
                "LENS: " + defaultString(shot.get("lensSuggestion"), "phone wide"),
                "FPS: " + defaultString(firstNonNull(shot.get("fps"), nested(shot, "cinematicExecution", "recommendedFPS")), "30"),
                "MOVE: " + defaultString(firstNonNull(shot.get("cameraMovement"), nested(shot, "cinematicExecution", "cameraStyle")), "static"),
                "TRANS: " + defaultString(shot.get("transition"), "hard cut")
        );
        int y = startY + line;
        for (String item : cameraLines) {
            y = drawWrappedText(g, upper(item), margin, y, columnW, line, 1);
        }

        List<String> performanceLines = new ArrayList<>();
        performanceLines.add("EXPR: " + defaultString(shot.get("expression"), "natural"));
        performanceLines.add("EMOTION: " + defaultString(shot.get("emotion"), "clear intent"));
        performanceLines.add("LIGHT: " + defaultString(shot.get("lighting"), "soft available light"));
        performanceLines.add("COMP: " + defaultString(shot.get("composition"), "center-safe"));
        performanceLines.add("SOUND: " + storyboardSoundNote(shot));
        performanceLines.add("TIP: " + beginnerTip(shot));
        y = startY + line;
        int rightX = margin + columnW + columnGap;
        for (String item : performanceLines) {
            y = drawWrappedText(g, upper(item), rightX, y, columnW, line, 1);
        }
    }

    private void drawFooterPromptMark(Graphics2D g, Color ink, String prompt, RenderSize size, int margin) {
        g.setColor(ink);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(14, size.width() / 90)));
        String marker = "ONE SHOT STORYBOARD SKETCH - PROMPT STORED WITH SCENE";
        g.drawString(marker, margin, size.height() - margin / 2);
    }

    private void drawLabel(Graphics2D g, String label, String value, int x, int y) {
        g.drawString(label + ": " + upper(value), x, y);
    }

    private void drawCentered(Graphics2D g, String text, int centerX, int baselineY, int maxWidth) {
        String value = fitText(g, text, maxWidth);
        g.drawString(value, centerX - textWidth(g, value) / 2, baselineY);
    }

    private int drawWrappedText(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight, int maxLines) {
        List<String> lines = wrapText(g, text, maxWidth, maxLines);
        int cursorY = y;
        for (String line : lines) {
            g.drawString(line, x, cursorY);
            cursorY += lineHeight;
        }
        return cursorY;
    }

    private List<String> wrapText(Graphics2D g, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        for (String paragraph : text.split("\\R")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(g, candidate) <= maxWidth) {
                    line = new StringBuilder(candidate);
                } else {
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                    }
                    line = new StringBuilder(fitText(g, word, maxWidth));
                }
                if (lines.size() >= maxLines) {
                    return lines;
                }
            }
            if (!line.isEmpty() && lines.size() < maxLines) {
                lines.add(line.toString());
            }
            if (lines.size() >= maxLines) {
                return lines;
            }
        }
        return lines;
    }

    private String fitText(Graphics2D g, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (textWidth(g, value) <= maxWidth) {
            return value;
        }
        while (value.length() > 4 && textWidth(g, value + "...") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "...";
    }

    private int textWidth(Graphics2D g, String text) {
        FontMetrics metrics = g.getFontMetrics();
        return metrics.stringWidth(text == null ? "" : text);
    }

    private void drawArrow(Graphics2D g, int x1, int y1, int x2, int y2) {
        g.setStroke(new BasicStroke(3f));
        g.drawLine(x1, y1, x2, y2);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int size = 16;
        Polygon head = new Polygon();
        head.addPoint(x2, y2);
        head.addPoint((int) (x2 - size * Math.cos(angle - Math.PI / 6)), (int) (y2 - size * Math.sin(angle - Math.PI / 6)));
        head.addPoint((int) (x2 - size * Math.cos(angle + Math.PI / 6)), (int) (y2 - size * Math.sin(angle + Math.PI / 6)));
        g.fillPolygon(head);
    }

    private String buildProductionImagePrompt(
            Map<String, Object> screenplayJson,
            Map<String, Object> shot,
            Map<String, Object> storyboardTag,
            String screenType,
            RenderSize size,
            GenerateStoryboardRequest request
    ) {
        Map<String, Object> screenplay = screenplayJson == null ? Map.of() : screenplayJson;
        Map<String, Object> shotJson = shot == null ? Map.of() : shot;
        Map<String, Object> product = mapValue(screenplay.get("productIntelligence"));
        boolean productLed = (request != null && Boolean.TRUE.equals(request.productLed()))
                || !product.isEmpty()
                || !mapValue(screenplay.get("productIntelligenceBrief")).isEmpty()
                || !defaultString(firstNonBlank(screenplay.get("productName"), shotJson.get("productName")), "").isBlank();
        String productName = defaultString(firstNonBlank(
                product.get("productName"),
                product.get("name"),
                shotJson.get("productName"),
                screenplay.get("projectTitle")
        ), "the supplied product");
        String shotType = defaultString(firstNonBlank(shotJson.get("shotType"), storyboardTag == null ? null : storyboardTag.get("shotType")), "Hero Shot");
        String requestedPrompt = request == null ? "" : defaultString(request.imagePrompt(), "");
        String basePrompt = defaultString(firstNonBlank(
                requestedPrompt,
                shotJson.get("productImagePrompt"),
                shotJson.get("productionImagePrompt"),
                shotJson.get("imagePrompt"),
                shotJson.get("storyboardImagePrompt"),
                shotJson.get("visualPrompt"),
                shotJson.get("visual"),
                shotJson.get("description")
        ), "Create a polished commercial " + shotType + " for " + productName + ".");
        String productionTreatment = productLed
                ? "Render as premium photoreal CGI product advertising: physically plausible materials, immaculate reflections and shadows, macro surface detail, precise commercial lighting, and a polished global-campaign finish."
                : "Render as a photoreal production still with cinematic lighting, natural materials, and a finished commercial grade.";
        String referenceDetails = request == null ? "" : defaultString(request.productReferenceDetails(), "");
        String negativePrompt = defaultString(firstNonBlank(shotJson.get("negativePrompt"), shotJson.get("negative_prompt")),
                "no package mutation, no logo drift, no label changes, no warped product, no duplicate product, no unreadable text, no watermark");
        boolean noHumans = "true".equalsIgnoreCase(stringValue(firstNonNull(shotJson.get("noHumans"), screenplay.get("noHumans"))));
        String humanRule = noHumans
                ? "Hard exclusion: no people, faces, hands, arms, bodies, human silhouettes, human reflections, presenters, or crowds."
                : "Do not introduce a person unless the screenplay explicitly requires one.";
        String aspectRatio = "horizontal".equals(screenType) ? "16:9" : "9:16";
        Map<String, Object> safeStoryboardTag = storyboardTag == null ? Map.of() : storyboardTag;
        Map<String, Object> overlayPlan = mapValue(firstNonNull(
                safeStoryboardTag.get("overlayPlan"),
                shotJson.get("overlayPlan")
        ));
        Map<String, Object> typographySystem = mapValue(firstNonNull(
                firstNonNull(safeStoryboardTag.get("typographySystem"), shotJson.get("typographySystem")),
                screenplay.get("typographySystem")
        ));
        String overlayExecution = plannedOverlayInstruction(
                frameOverlayText(safeStoryboardTag, shotJson),
                overlayPlan,
                typographySystem
        );
        return """
                Create one final, production-quality advertising still that will be used as an image-to-video anchor.
                This must look like a finished cinematic commercial frame, never a storyboard, sketch, contact sheet, grid, diagram, mood board, or frame with production labels.

                Product: %s
                Shot type: %s
                Creative brief: %s
                Production treatment: %s
                Product reference notes: %s

                Output: exact %sx%s, %s composition. Keep the product as the stable focal subject with clean mobile-safe framing and commercial lighting.
                Preserve exact identity from canonical project references and any client-review reference explicitly marked EXACT_SOURCE.
                Obey each client-review image's explicit reference intent: INSPIRATION_ONLY supplies mood, composition, lighting, palette, texture, and pacing without copied branding; EXACT_SOURCE is the approved visual source of truth for that shot.
                Unless EXACT_SOURCE is selected, the product name, packaging, and visible copy must follow this project's approved screenplay and product data.
                Planned typography direction: %s
                Do not invent claims, labels, logos, ingredients, accessories, or unplanned text overlays. Render only the approved overlay above when it is enabled. %s
                Negative prompt: %s
                """.formatted(productName, shotType, basePrompt, productionTreatment, referenceDetails, size.width(), size.height(), aspectRatio, overlayExecution, humanRule, negativePrompt).trim();
    }

    private String buildStoryboardPrompt(
            Map<String, Object> screenplayJson,
            Map<String, Object> shot,
            Map<String, Object> storyboardTag,
            Map<String, Object> lightingBuildSheetTag,
            Map<String, Object> cameraPlanSheetTag,
            String screenType,
            RenderSize size
    ) {
        String override = stringValue(storyboardTag == null ? null : storyboardTag.get("imageGenerationPromptOverride"));
        if (!override.isBlank()) {
            return appendStoryboardReferenceGuidance(override, screenplayJson);
        }

        Map<String, Object> screenplay = screenplayJson == null ? Map.of() : screenplayJson;
        Map<String, Object> tag = storyboardTag == null ? Map.of() : storyboardTag;
        Map<String, Object> lightingTag = lightingBuildSheetTag == null ? Map.of() : lightingBuildSheetTag;
        Map<String, Object> cameraTag = cameraPlanSheetTag == null ? Map.of() : cameraPlanSheetTag;
        Map<String, Object> shotJson = shot == null ? Map.of() : shot;
        Map<String, Object> primaryCharacter = firstMapValue(firstNonBlank(tag.get("primaryCharacters"), shotJson.get("primaryCharacters"), shotJson.get("characters")));

        boolean horizontal = "horizontal".equals(screenType);
        String aspectRatio = horizontal ? "16:9" : "9:16";
        String composition = horizontal ? "16:9 horizontal composition" : "9:16 vertical composition";
        int shotNumber = intValue(firstNonNull(tag.get("shotNumber"), shotJson.get("shotNumber")), 1);
        String projectTitle = defaultString(storyboardValue(tag, shotJson, "projectTitle", "projectName"), defaultString(screenplay.get("projectTitle"), "PROJECT"));
        String title = defaultString(storyboardValue(tag, shotJson, "shotTitle", "title", "description"), "Storyboard Shot " + shotNumber);
        String sceneLocation = defaultString(storyboardValue(tag, shotJson, "sceneLocation", "environment", "setDesign"), "creator shooting space");
        String narrativeBeat = defaultString(storyboardValue(tag, shotJson, "narrativeBeatSummary", "beatTitle", "purpose"), title);
        String shotTypeFullName = defaultString(storyboardValue(tag, shotJson, "shotTypeFullName"), defaultString(storyboardValue(tag, shotJson, "shotType"), "Shot"));
        String cameraAngle = defaultString(storyboardValue(tag, shotJson, "cameraAngle"), "Eye Level");
        String movement = defaultString(storyboardValue(tag, shotJson, "cameraMovement"), defaultString(nested(shotJson, "cinematicExecution", "cameraStyle"), "Static"));
        String lens = defaultString(storyboardValue(tag, shotJson, "lensSuggestion"), "Mobile 1x Wide");
        Map<String, Object> visualTreatment = mapValue(shotJson.get("visualTreatment"));
        String visualTreatmentSummary = compactPromptParts(
                promptLabel("motion", firstNonBlank(visualTreatment.get("motionStyle"), nested(shotJson, "cinematicExecution", "captureMode"))),
                promptLabel("colour", visualTreatment.get("colorGrade")),
                promptLabel("effect", visualTreatment.get("editorialEffect")),
                promptLabel("notes", visualTreatment.get("notes"))
        );
        String compositionSummary = defaultString(storyboardValue(tag, shotJson, "compositionSummary", "composition"), "center-safe framing");
        String environment = defaultString(storyboardValue(tag, shotJson, "environment", "setDesign", "sceneLocation"), sceneLocation);
        String keyLight = defaultString(firstNonBlank(tag.get("keyLightSourceLabel"), lightingTag.get("keyLight"), nested(lightingTag, "floorPlan", "keyLight")), "motivated practical key");
        String expression = defaultString(storyboardValue(tag, shotJson, "expression"), "natural performance");
        String emotion = defaultString(storyboardValue(tag, shotJson, "emotion"), "clear intent");
        String emotionIntensity = defaultString(storyboardValue(tag, shotJson, "emotionIntensity"), "0");
        String bodyLanguage = defaultString(storyboardValue(tag, shotJson, "bodyLanguage"), "natural posture");
        String headroom = defaultString(storyboardValue(tag, shotJson, "headroomNote"), "clean headroom");
        String frameLeft = defaultString(storyboardValue(tag, shotJson, "frameLeftNote"), "visible left-frame anchor from shot");
        String frameRight = defaultString(storyboardValue(tag, shotJson, "frameRightNote"), "visible right-frame anchor from shot");
        String target = defaultString(storyboardValue(tag, shotJson, "targetFocalPoint", "retentionGoal"), "TARGET: primary face/action");
        String lightingAtmosphere = defaultString(storyboardValue(tag, shotJson, "lightingAtmosphericDescription", "lighting"), "soft practical light");
        String cinematicIntent = defaultString(firstNonBlank(lightingTag.get("cinematicIntent"), lightingAtmosphere), "clear emotional lighting intent");
        String inferredTone = defaultString(firstNonBlank(tag.get("inferredTone"), shotJson.get("inferredTone"), screenplay.get("inferredTone"), screenplay.get("emotionalArc")), "cinematic creator tone");
        String primaryCharacters = defaultString(firstNonBlank(tag.get("primaryCharacters"), shotJson.get("primaryCharacters"), shotJson.get("characters"), firstCharacterDescription(tag, shotJson)), "Primary visible character from the shot JSON.");
        String sideCharacters = defaultString(firstNonBlank(tag.get("sideCharacters"), shotJson.get("sideCharacters")), "none");
        String wardrobe = defaultString(firstNonBlank(primaryCharacter.get("wardrobeThisShot"), primaryCharacter.get("wardrobe"), shotJson.get("wardrobeThisShot"), shotJson.get("characterContinuity")), "match character continuity");
        String blockingNotes = defaultString(firstNonBlank(shotJson.get("blockingNotes"), cameraTag.get("blockingMap"), tag.get("directorNote"), storyboardValue(tag, shotJson, "action", "primaryActorAction", "visualDirection", "description")), "show the planned actor blocking clearly");
        String setDesign = defaultString(storyboardValue(tag, shotJson, "setDesign", "environment"), environment);
        String keyProps = defaultString(firstNonBlank(shotJson.get("keyProps"), shotJson.get("props"), nested(cameraTag, "blockingMap", "keyProps"), shotJson.get("resourceRequirements")), "only props specified by the shot");
        String culturalReferences = defaultString(firstNonBlank(tag.get("culturalReferences"), shotJson.get("culturalReferences")), "none");
        String textOverlay = frameOverlayText(tag, shotJson);
        Map<String, Object> overlayPlan = mapValue(firstNonNull(tag.get("overlayPlan"), shotJson.get("overlayPlan")));
        Map<String, Object> typographySystem = mapValue(firstNonNull(
                firstNonNull(tag.get("typographySystem"), shotJson.get("typographySystem")),
                screenplay.get("typographySystem")
        ));
        String overlayExecution = plannedOverlayInstruction(textOverlay, overlayPlan, typographySystem);
        String dialogueLanguage = defaultString(firstNonBlank(tag.get("dialogueLanguage"), shotJson.get("dialogueLanguage"), screenplay.get("dialogueLanguage")), "English");
        String dialogueBox = dialogueBoxText(shotJson, tag);
        String ambient = defaultString(storyboardValue(tag, shotJson, "ambientBedDescription"), storyboardSoundNote(shotJson));
        String sync = defaultString(storyboardValue(tag, shotJson, "syncHitDescription"), "natural sync hit from action");
        String music = backgroundMusicNote(shotJson);
        String musicCue = "none".equalsIgnoreCase(music) ? defaultString(screenplay.get("backgroundMusicPlan"), "none") : music;
        String directorTip = defaultString(storyboardValue(tag, shotJson, "directorNote", "creatorTip"), beginnerTip(shotJson));
        String creatorGuides = compactPromptParts(
                promptLabel("director", directorTip),
                promptLabel("shot direction", shotJson.get("creatorDirection")),
                promptLabel("visual treatment", visualTreatmentSummary),
                promptLabel("creator guide", shotJson.get("rookieFriendlyGuide")),
                promptLabel("resources", firstNonBlank(screenplay.get("resourceRequirements"), shotJson.get("resourceRequirements"))),
                promptLabel("post", firstNonBlank(shotJson.get("postProductionNotes"), screenplay.get("postProductionNotes")))
        );
        String generatedPrompt = """
                Generate a multi-panel, highly technical production planning storyboard diagram.

                STYLE:
                Indian creator production storyboard sketch sheet, hand-drawn animatic linework, loose pencil construction marks, clean ink outlines, selective muted marker color accents, readable planning labels, visible wardrobe/set cues, clear practical lighting notes, production-board texture. Exact %sx%s output, %s, aspect ratio %s.

                [STRICT MULTI-PANEL GRID STRUCTURE]
                You must structure the image as a professional multi-cell technical sheet using clean black borders:
                1. HEADER BAND (Top 10%%): Clearly divide into blocks displaying:
                   - Project: %s | Format: %s
                   - Shot %s: %s | Scene/Location: %s
                   - Beat: %s
                2. LEFT METADATA BAR (20%% Width): Stacked data cards containing crisp, readable text for:
                   - CAMERA SETUP: %s, %s, %s, %s
                   - COMPOSITION: %s
                   - ENVIRONMENT: %s
                   - LIGHTING: %s
                3. RIGHT METADATA BAR (20%% Width): Stacked data cards containing crisp, readable text for:
                   - PERFORMANCE: Expression: %s (Intensity: %s), Action: %s
                   - FRAME NOTES: %s | Left: %s | Right: %s
                   - TARGET: %s
                4. CENTRAL PRODUCTION HUB (60%% Width, Large Center Panel):
                   - Render a storyboard sketch panel depicting the scene: %s. It must read as a drawn planning frame, not a generated photo or final cinematic still.
                   - Character visual profile: %s. Side cast: %s. Wardrobe: %s.
                   - Set details: %s with key elements: %s. Include cultural references only if present: %s.
                   - Tone/Lighting execution: %s, applying %s matching the goal: %s.

                [PLANNED ON-SCREEN TYPOGRAPHY & AUDIO CALLOUTS]
                %s
                - Directly beneath the central illustration, render a clean text card block displaying the primary character's current actions and dialogue delivery notes in %s: %s

                [FOOTER BAND (Bottom 10%%)]
                - Split into technical parameter blocks for audio routing and staging:
                  - Left Panel: SOUND & AMBIENT NOTES: ambient bed %s; sync hit %s
                  - Center Panel: MUSIC CUES: %s
                  - Right Panel: DIRECTOR & CREATOR SETUP GUIDES: %s
                - Very bottom baseline: Include a standardized mapping legend explaining directional action lines: "--> = Eye / Body movement" and "---> = Glance / Attention shift".

                [CRITICAL IMAGE EXECUTION RULES]
                - All label text must be clean, human-legible print font inside boxes. Do not let text bleed, overlap, or truncate over borders.
                - Ensure distinct foreground, midground, and background separation within the central scene window.
                - Keep the output looking like an exhaustive production storyboard sketch sheet with the central frame readable as the intended shot, not an empty cinematic film capture frame.
                - Do not create a plain single-frame still. Do not create poster art. Do not generate a black-and-white/grayscale photo, monochrome cinematic render, glossy color-graded still, photorealistic gradient render, UI chrome, watermark, or markdown. The central visual must keep storyboard sketch linework, rough planning strokes, and selective muted color accents.
                - Use only the characters, wardrobe, props, setting, camera notes, dialogue, and cultural references supplied below. Do not invent extra people, props, logos, or locations.
                - The shot-scoped continuity summaries below are source facts only; never render their syntax in the image.

                [GLOBAL CONTINUITY BIBLE]
                %s

                [ADJACENT-SHOT EDIT BRIDGE]
                %s

                [COMPLETE CURRENT-SHOT DIRECTOR PACKET]
                %s

                [CURRENT-SHOT DEPARTMENT PLANS]
                Storyboard continuity: %s
                Lighting continuity: %s
                Camera continuity: %s
                Current-shot continuity: %s

                Treat all sections above as one coherent specification. Do not discard director, timing, product,
                camera, lighting, focus, transition, typography, or boundary-state detail merely to shorten the prompt.
                """.formatted(
                size.width(),
                size.height(),
                composition,
                aspectRatio,
                truncatePromptText(projectTitle, 80),
                truncatePromptText(screenType, 30),
                shotNumber,
                truncatePromptText(title, 80),
                truncatePromptText(sceneLocation, 90),
                truncatePromptText(narrativeBeat, 110),
                truncatePromptText(shotTypeFullName, 50),
                truncatePromptText(cameraAngle, 50),
                truncatePromptText(movement, 50),
                truncatePromptText(lens, 50),
                truncatePromptText(compositionSummary, 120),
                truncatePromptText(environment, 120),
                truncatePromptText(keyLight, 120),
                truncatePromptText(expression, 80),
                truncatePromptText(emotionIntensity, 20),
                truncatePromptText(bodyLanguage, 95),
                truncatePromptText(headroom, 70),
                truncatePromptText(frameLeft, 90),
                truncatePromptText(frameRight, 90),
                truncatePromptText(target, 110),
                truncatePromptText(blockingNotes, 280),
                truncatePromptText(primaryCharacters, 320),
                truncatePromptText(sideCharacters, 160),
                truncatePromptText(wardrobe, 160),
                truncatePromptText(setDesign, 220),
                truncatePromptText(keyProps, 220),
                truncatePromptText(culturalReferences, 160),
                truncatePromptText(inferredTone, 90),
                truncatePromptText(lightingAtmosphere, 160),
                truncatePromptText(cinematicIntent, 180),
                truncatePromptText(overlayExecution, 520),
                truncatePromptText(dialogueLanguage, 40),
                truncatePromptText(dialogueBox, 180),
                truncatePromptText(ambient, 110),
                truncatePromptText(sync, 110),
                truncatePromptText(musicCue, 120),
                truncatePromptText(creatorGuides, 180),
                toJson(globalImageContinuityContext(screenplay)),
                toJson(adjacentShotImageContinuity(screenplay, shotJson, shotNumber)),
                toJson(currentShotDirectorPacket(screenplay, shotJson, shotNumber)),
                toJson(compactImageContext(tag,
                        "shotNumber", "shotTitle", "title", "action", "visualDirection", "compositionSummary",
                        "environment", "primaryCharacters", "sideCharacters", "wardrobe", "setDesign", "keyProps",
                        "overlayPlan", "typographySystem", "dialogueLanguage", "dialogue", "directorNote")),
                toJson(compactImageContext(lightingTag,
                        "keyLight", "fillLight", "rimLight", "practicals", "lightingAtmosphericDescription",
                        "cinematicIntent", "colorTemperature", "contrastRatio", "floorPlan")),
                toJson(compactImageContext(cameraTag,
                        "shotType", "shotTypeFullName", "cameraAngle", "cameraMovement", "lensSuggestion",
                        "focusPlan", "blockingMap", "compositionSummary", "targetFocalPoint")),
                toJson(shotScopedImageContext(shotJson))
        );
        return appendStoryboardReferenceGuidance(generatedPrompt, screenplay);
    }
    private String storyboardValue(Map<String, Object> storyboardTag, Map<String, Object> shot, String... keys) {
        for (String key : keys) {
            String value = promptText(storyboardTag == null ? null : storyboardTag.get(key));
            if (!value.isBlank()) {
                return value;
            }
            value = promptText(shot == null ? null : shot.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstCharacterDescription(Map<String, Object> storyboardTag, Map<String, Object> shot) {
        Map<String, Object> character = firstMapValue(storyboardTag == null ? null : storyboardTag.get("primaryCharacters"));
        if (character.isEmpty()) {
            character = firstMapValue(shot == null ? null : firstNonNull(shot.get("primaryCharacters"), shot.get("characters")));
        }
        if (character.isEmpty()) {
            return defaultString(storyboardValue(storyboardTag, shot, "character", "characterContinuity", "cast", "creatorDirection"), "Primary visible character from the shot JSON.");
        }
        Map<String, Object> hair = mapValue(character.get("hair"));
        return compactPromptParts(
                promptLabel("name", firstNonBlank(character.get("storyCharacterName"), character.get("characterName"), character.get("assignedActorName"))),
                promptLabel("archetype", character.get("archetypeLabel")),
                promptLabel("age", character.get("age")),
                promptLabel("gender", character.get("gender")),
                promptLabel("ethnicity", character.get("ethnicity")),
                promptLabel("hair", compactPromptParts(hair.get("style"), hair.get("length"), hair.get("color"))),
                promptLabel("wardrobe", firstNonBlank(character.get("wardrobeThisShot"), character.get("wardrobe"))),
                promptLabel("features", character.get("distinguishingFeatures")),
                promptLabel("visual profile", character.get("assignedActorVisualProfile")),
                promptLabel("posture", character.get("postureBaseline"))
        );
    }

    private String propsAndSetDetails(Map<String, Object> shot, Map<String, Object> storyboardTag) {
        return compactPromptParts(
                promptLabel("set", firstNonBlank(storyboardTag.get("setDesign"), shot.get("setDesign"), storyboardTag.get("environment"), shot.get("environment"))),
                promptLabel("visible cultural references", firstNonBlank(storyboardTag.get("culturalReferences"), shot.get("culturalReferences"))),
                promptLabel("props", firstNonBlank(shot.get("props"), shot.get("keyProps"), shot.get("resourceRequirements"), shot.get("continuityProps"))),
                promptLabel("screen/UI details", firstNonBlank(shot.get("screenContent"), shot.get("laptopScreen"), shot.get("phoneScreen"), shot.get("uiDetails"), shot.get("textOverlay"))),
                promptLabel("continuity notes", firstNonBlank(shot.get("continuityNotes"), shot.get("blockingNotes"), storyboardTag.get("headroomNote")))
        );
    }

    private String dialogueBoxText(Map<String, Object> shot, Map<String, Object> storyboardTag) {
        String canonicalShotDialogue = dialogueLine(shot);
        if (!canonicalShotDialogue.isBlank()) {
            return canonicalShotDialogue;
        }
        Map<String, Object> primaryDialogue = mapValue(storyboardTag == null ? null : storyboardTag.get("primaryDialogue"));
        String line = promptText(primaryDialogue.get("line"));
        if (line.isBlank()) {
            return "No dialogue in this shot.";
        }
        String speaker = defaultString(firstNonBlank(primaryDialogue.get("characterName"), primaryDialogue.get("archetypeLabel")), "Speaker");
        String subtext = promptText(primaryDialogue.get("subtext"));
        if (!subtext.isBlank()) {
            return "%s: \"%s\" (%s).".formatted(speaker, line, subtext);
        }
        return "%s: \"%s\".".formatted(speaker, line);
    }

    private String frameOverlayText(Map<String, Object> storyboardTag, Map<String, Object> shot) {
        Map<String, Object> overlayPlan = mapValue(firstNonNull(
                storyboardTag.get("overlayPlan"),
                shot.get("overlayPlan")
        ));
        if (Boolean.FALSE.equals(overlayPlan.get("enabled"))) return "";
        String overlay = defaultString(firstNonBlank(
                overlayPlan.get("text"),
                storyboardValue(storyboardTag, shot, "textOverlay")
        ), "");
        return overlay
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String plannedOverlayInstruction(
            String text,
            Map<String, Object> overlayPlan,
            Map<String, Object> typographySystem
    ) {
        if (text == null || text.isBlank() || Boolean.FALSE.equals(overlayPlan.get("enabled"))) {
            return "- No promotional text overlay is enabled for this shot. Do not invent a banner, caption, slogan, product claim, emoji, or decorative lettering. Preserve clean negative space only when the composition plan requests it.";
        }
        String font = defaultString(firstNonBlank(
                overlayPlan.get("fontFamily"),
                typographySystem.get("primaryFont")
        ), "Inter");
        String weight = defaultString(firstNonBlank(
                overlayPlan.get("fontWeight"),
                typographySystem.get("primaryWeight")
        ), "800");
        String position = defaultString(overlayPlan.get("position"), "Lower safe zone");
        String background = defaultString(firstNonBlank(
                overlayPlan.get("backgroundStyle"),
                typographySystem.get("backgroundStyle")
        ), "No panel");
        String entrance = defaultString(firstNonBlank(
                overlayPlan.get("entrance"),
                typographySystem.get("defaultEntrance")
        ), "Fade");
        String speed = defaultString(firstNonBlank(
                overlayPlan.get("speed"),
                typographySystem.get("defaultSpeed")
        ), "Measured");
        return "- Render the approved on-screen copy exactly as written, with no additional words or emojis: \""
                + text + "\". Typography: " + font + ", weight " + weight + ", " + background
                + ", positioned in the " + position + ". Protect the product and faces from overlap. Add a small technical motion callout for post-production: "
                + entrance + " at " + speed + " pacing. Do not invent claims, ingredients, nutrition values, offers, logos, or product names.";
    }

    private String storyboardTiming(Map<String, Object> storyboardTag, Map<String, Object> shot) {
        String start = defaultString(firstNonBlank(storyboardTag.get("startTimeSeconds"), shot.get("startTimeSeconds"), shot.get("startTime")), "0.0");
        String end = defaultString(firstNonBlank(storyboardTag.get("endTimeSeconds"), shot.get("endTimeSeconds"), shot.get("endTime")), "0.0");
        return start + " - " + end;
    }

    private Object firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = promptText(value);
            if (!text.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Map<String, Object> firstMapValue(Object value) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = mapValue(item);
                if (!map.isEmpty()) {
                    return map;
                }
            }
        }
        return mapValue(value);
    }

    private String promptLabel(String label, Object value) {
        String text = promptText(value);
        return text.isBlank() ? "" : label + ": " + text;
    }

    private String compactPromptParts(Object... parts) {
        List<String> values = new ArrayList<>();
        for (Object part : parts) {
            String text = promptText(part);
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return String.join("; ", values);
    }

    private String promptText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            String json = toJson(value);
            return "{}".equals(json) || "[]".equals(json) ? "" : json;
        }
        return String.valueOf(value).replaceAll("\\s+", " ").trim();
    }

    private String buildProductionSheetPrompt(
            String imageKind,
            Map<String, Object> shot,
            Map<String, Object> screenplayJson,
            Map<String, Object> tag,
            String screenType,
            RenderSize size
    ) {
        String override = stringValue(tag == null ? null : tag.get("imageGenerationPromptOverride"));
        if (!override.isBlank()) {
            return override;
        }
        String title = "lighting".equals(imageKind) ? "rookie-executable lighting build sheet" : "shoot-ready camera plan sheet";
        String sheetFocus = "lighting".equals(imageKind)
                ? "show exact light placement, subject position, phone position, practical/window sources, shadows, and quick setup steps"
                : "show exact camera body position, lens choice, framing box, movement path, subject blocking, and safe-frame notes";
        return """
                Professional color production planning sheet, %s, exact %sx%s output, %s composition.
                This is for one specific screenplay shot, not a generic film diagram. %s.
                Render as a clear storyboard-adjacent technical diagram with readable labels, top-down map, perspective sketch, numbered cards, and checklist steps.
                Keep all text large enough for mobile review. Use the supplied JSON exactly; do not invent missing values.
                Shot JSON to match:
                %s
                Source production-plan JSON:
                %s
                Complete screenplay JSON for continuity:
                %s
                """.formatted(
                title,
                size.width(),
                size.height(),
                "horizontal".equals(screenType) ? "16:9 horizontal" : "9:16 vertical",
                sheetFocus,
                toJson(shot == null ? Map.of() : shot),
                toJson(tag == null ? Map.of() : tag),
                toJson(screenplayJson == null ? Map.of() : screenplayJson)
        );
    }

    private String buildAiShotEditPrompt(
            String basePrompt,
            String mode,
            String instruction,
            Map<String, Object> continuityContext
    ) {
        return """
                %s

                [USER AI SHOT CHANGE]
                Mode: %s
                Requested change: %s

                [CONTINUITY LOCK]
                Apply the requested change only to this shot. Keep character identity, wardrobe, eyeline, actor blocking, timeline order, dialogue meaning, screen direction, color palette, set geography, and camera language consistent with adjacent shots unless the user explicitly asks to change them.
                Use the previous and next shot context below to keep the new frame synchronized with the sequence.
                Continuity context JSON: %s
                """.formatted(
                basePrompt,
                defaultString(mode, "edit_existing_shot"),
                truncatePromptText(instruction, 1200),
                toJson(continuityContext == null ? Map.of() : continuityContext)
        );
    }

    private Map<String, Object> aiEditMetadata(String mode, String instruction, Map<String, Object> continuityContext) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", defaultString(mode, "edit_existing_shot"));
        metadata.put("instruction", truncatePromptText(instruction, 1200));
        metadata.put("continuityContext", continuityContext == null ? Map.of() : continuityContext);
        metadata.put("editedAt", OffsetDateTime.now().toString());
        metadata.put("provider", properties.getAi().isStoryboardImageGenerationEnabled() ? "gemini" : "local");
        metadata.put("model", properties.getAi().isStoryboardImageGenerationEnabled() ? properties.getAi().getGeminiImageModel() : "local_storyboard_sketch_v1");
        return metadata;
    }

    private Map<String, Object> shotContinuityContext(
            List<Map<String, Object>> shots,
            Map<Integer, CreatorScriptShotPlan> planByShotNumber,
            int shotNumber
    ) {
        return timelineContinuityContext(shots, planByShotNumber, shotNumber - 1, shotNumber, shotNumber + 1);
    }

    private Map<String, Object> timelineContinuityContext(
            List<Map<String, Object>> shots,
            Map<Integer, CreatorScriptShotPlan> planByShotNumber,
            int previousShotNumber,
            int currentShotNumber,
            int nextShotNumber
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("previousShotNumber", previousShotNumber);
        context.put("currentShotNumber", currentShotNumber);
        context.put("nextShotNumber", nextShotNumber);
        Map<String, Object> previous = compactShotContext(shotByNumber(shots, previousShotNumber), planByShotNumber.get(previousShotNumber));
        Map<String, Object> current = compactShotContext(shotByNumber(shots, currentShotNumber), planByShotNumber.get(currentShotNumber));
        Map<String, Object> next = compactShotContext(shotByNumber(shots, nextShotNumber), planByShotNumber.get(nextShotNumber));
        if (!previous.isEmpty()) {
            context.put("previousShot", previous);
        }
        if (!current.isEmpty()) {
            context.put("currentShot", current);
        }
        if (!next.isEmpty()) {
            context.put("nextShot", next);
        }
        context.put("guardrails", List.of(
                "do not change actor identity or wardrobe continuity unless requested",
                "keep timeline logic and screen direction consistent",
                "keep dialogue meaning and performance intent aligned",
                "match set geography, lighting motivation, and camera language around this shot"
        ));
        return context;
    }

    private Map<String, Object> compactShotContext(Map<String, Object> shot, CreatorScriptShotPlan plan) {
        if ((shot == null || shot.isEmpty()) && plan == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        if (shot != null && !shot.isEmpty()) {
            putPromptValue(context, "shotNumber", shot.get("shotNumber"));
            putPromptValue(context, "title", firstNonBlank(shot.get("title"), shot.get("description")));
            putPromptValue(context, "time", compactPromptParts(shot.get("startTime"), shot.get("endTime")));
            putPromptValue(context, "action", firstNonBlank(shot.get("action"), shot.get("primaryActorAction"), shot.get("visualDirection"), shot.get("description")));
            putPromptValue(context, "environment", firstNonBlank(shot.get("environment"), shot.get("setDesign")));
            putPromptValue(context, "cameraAngle", shot.get("cameraAngle"));
            putPromptValue(context, "cameraMovement", firstNonBlank(shot.get("cameraMovement"), nested(shot, "cinematicExecution", "cameraStyle")));
            putPromptValue(context, "shotType", shot.get("shotType"));
            putPromptValue(context, "lensSuggestion", shot.get("lensSuggestion"));
            putPromptValue(context, "composition", shot.get("composition"));
            putPromptValue(context, "dialogue", firstNonBlank(shot.get("dialogue"), shot.get("voiceOver")));
            putPromptValue(context, "textOverlay", shot.get("textOverlay"));
            putPromptValue(context, "sound", firstNonBlank(shot.get("ambientBedDescription"), shot.get("syncHitDescription"), shot.get("soundDesign")));
            putPromptValue(context, "characters", firstNonBlank(shot.get("primaryCharacters"), shot.get("characters"), shot.get("characterContinuity")));
            putPromptValue(context, "wardrobe", firstNonBlank(shot.get("wardrobeThisShot"), shot.get("wardrobe"), shot.get("characterContinuity")));
        }
        Map<String, Object> planContext = compactPlanContext(plan);
        if (!planContext.isEmpty()) {
            context.put("productionPlan", planContext);
        }
        return context;
    }

    private Map<String, Object> compactPlanContext(CreatorScriptShotPlan plan) {
        if (plan == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> storyboardTag = plan.getStoryboardTag() == null ? Map.of() : plan.getStoryboardTag();
        Map<String, Object> lightingTag = plan.getLightingBuildSheetTag() == null ? Map.of() : plan.getLightingBuildSheetTag();
        Map<String, Object> cameraTag = plan.getCameraPlanSheetTag() == null ? Map.of() : plan.getCameraPlanSheetTag();
        putPromptValue(context, "storyBeat", firstNonBlank(storyboardTag.get("shotTitle"), storyboardTag.get("narrativeBeatSummary")));
        putPromptValue(context, "setDesign", firstNonBlank(storyboardTag.get("setDesign"), storyboardTag.get("environment"), storyboardTag.get("sceneLocation")));
        putPromptValue(context, "performance", firstNonBlank(storyboardTag.get("expression"), storyboardTag.get("bodyLanguage"), storyboardTag.get("directorNote")));
        putPromptValue(context, "lighting", firstNonBlank(storyboardTag.get("lightingAtmosphericDescription"), lightingTag.get("cinematicIntent"), lightingTag.get("keyLight")));
        putPromptValue(context, "camera", firstNonBlank(storyboardTag.get("cameraAngle"), storyboardTag.get("cameraMovement"), cameraTag.get("cameraRig"), cameraTag.get("movementSpec")));
        putPromptValue(context, "frame", firstNonBlank(storyboardTag.get("compositionSummary"), cameraTag.get("framePreview")));
        putPromptValue(context, "audio", firstNonBlank(storyboardTag.get("ambientBedDescription"), storyboardTag.get("syncHitDescription"), storyboardTag.get("soundDesign")));
        return context;
    }

    private void addStoryboardSceneContinuity(Map<String, Object> context, UUID storyboardId, int shotNumber) {
        if (context == null || storyboardId == null || shotNumber <= 0) {
            return;
        }
        Map<String, Object> previous = compactStoryboardSceneContext(storyboardId, shotNumber - 1);
        Map<String, Object> current = compactStoryboardSceneContext(storyboardId, shotNumber);
        Map<String, Object> next = compactStoryboardSceneContext(storyboardId, shotNumber + 1);
        if (!previous.isEmpty()) {
            context.put("persistedPreviousStoryboardScene", previous);
        }
        if (!current.isEmpty()) {
            context.put("persistedCurrentStoryboardScene", current);
        }
        if (!next.isEmpty()) {
            context.put("persistedNextStoryboardScene", next);
        }
    }

    private Map<String, Object> compactStoryboardSceneContext(UUID storyboardId, int shotNumber) {
        if (shotNumber <= 0) {
            return Map.of();
        }
        return sceneRepository.findByStoryboardIdAndShotNumber(storyboardId, shotNumber)
                .map(this::compactStoryboardSceneContext)
                .orElse(Map.of());
    }

    private Map<String, Object> compactStoryboardSceneContext(CreatorStoryboardScene scene) {
        if (scene == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        putPromptValue(context, "shotNumber", scene.getShotNumber());
        putPromptValue(context, "title", scene.getTitle());
        putPromptValue(context, "time", compactPromptParts(scene.getStartTime(), scene.getEndTime()));
        putPromptValue(context, "action", scene.getAction());
        putPromptValue(context, "environment", scene.getEnvironment());
        putPromptValue(context, "cameraAngle", scene.getCameraAngle());
        putPromptValue(context, "cameraMovement", scene.getCameraMovement());
        putPromptValue(context, "shotType", scene.getShotType());
        putPromptValue(context, "lensSuggestion", scene.getLensSuggestion());
        putPromptValue(context, "composition", scene.getComposition());
        putPromptValue(context, "dialogue", scene.getDialogue());
        putPromptValue(context, "textOverlay", scene.getTextOverlay());
        putPromptValue(context, "soundDesign", scene.getSoundDesign());
        Map<String, Object> metadata = scene.getMetadata() == null ? Map.of() : scene.getMetadata();
        putPromptValue(context, "storyboardTag", metadata.get("storyboardTag"));
        putPromptValue(context, "aiShotEdit", metadata.get("aiShotEdit"));
        return context;
    }

    private Map<String, Object> sceneToShotMap(CreatorStoryboardScene scene) {
        if (scene == null) {
            return Map.of();
        }
        Map<String, Object> shot = new LinkedHashMap<>();
        Map<String, Object> metadata = scene.getMetadata() == null ? Map.of() : scene.getMetadata();
        Map<String, Object> rawShot = mapValue(metadata.get("rawShot"));
        if (!rawShot.isEmpty()) {
            shot.putAll(rawShot);
        }
        shot.put("shotNumber", scene.getShotNumber());
        putPromptValue(shot, "startTime", scene.getStartTime());
        putPromptValue(shot, "endTime", scene.getEndTime());
        putPromptValue(shot, "durationSeconds", scene.getDurationSeconds());
        putPromptValue(shot, "title", scene.getTitle());
        putPromptValue(shot, "purpose", scene.getPurpose());
        putPromptValue(shot, "shotType", scene.getShotType());
        putPromptValue(shot, "cameraAngle", scene.getCameraAngle());
        putPromptValue(shot, "cameraMovement", scene.getCameraMovement());
        putPromptValue(shot, "lensSuggestion", scene.getLensSuggestion());
        putPromptValue(shot, "fps", scene.getFps());
        putPromptValue(shot, "composition", scene.getComposition());
        putPromptValue(shot, "expression", scene.getExpression());
        putPromptValue(shot, "emotion", scene.getEmotion());
        putPromptValue(shot, "bodyLanguage", scene.getBodyLanguage());
        putPromptValue(shot, "lighting", scene.getLighting());
        putPromptValue(shot, "environment", scene.getEnvironment());
        putPromptValue(shot, "action", scene.getAction());
        putPromptValue(shot, "voiceOver", scene.getVoiceOver());
        putPromptValue(shot, "dialogue", scene.getDialogue());
        putPromptValue(shot, "textOverlay", scene.getTextOverlay());
        putPromptValue(shot, "transition", scene.getTransition());
        putPromptValue(shot, "soundDesign", scene.getSoundDesign());
        putPromptValue(shot, "editingNotes", scene.getEditingNotes());
        putPromptValue(shot, "retentionGoal", scene.getRetentionGoal());
        putPromptValue(shot, "creatorDirection", scene.getCreatorDirection());
        putPromptValue(shot, "subtitlePosition", scene.getSubtitlePosition());
        putPromptValue(shot, "mobileFocusArea", scene.getMobileFocusArea());
        putPromptValue(shot, "safeZoneNotes", scene.getSafeZoneNotes());
        putPromptValue(shot, "executionDifficulty", scene.getExecutionDifficulty());
        putPromptValue(shot, "cinematicExecution", scene.getCinematicExecution());
        putPromptValue(shot, "rookieFriendlyGuide", scene.getRookieFriendlyGuide());
        return shot;
    }

    private String shotIdFromScene(CreatorStoryboardScene scene, int shotNumber) {
        if (scene != null) {
            CreatorAsset asset = findAsset(scene.getImageAssetId());
            String shotId = stringValue(asset == null || asset.getMetadata() == null ? null : asset.getMetadata().get("shotId"));
            if (!shotId.isBlank()) {
                return shotId;
            }
        }
        return "shot-%04d".formatted(shotNumber);
    }

    private Map<String, Object> buildInsertedTimelineShot(
            ShotTimelineInsertRequest request,
            List<Map<String, Object>> shots,
            int afterShotNumber,
            int insertShotNumber
    ) {
        Map<String, Object> previous = shotByNumber(shots, afterShotNumber);
        Map<String, Object> next = shotByNumber(shots, insertShotNumber);
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("shotNumber", insertShotNumber);
        shot.put("title", defaultString(request.title(), "AI Inserted Shot " + insertShotNumber));
        shot.put("purpose", "AI inserted bridge shot: " + request.instruction());
        shot.put("action", request.instruction());
        shot.put("durationSeconds", defaultInt(request.durationSeconds(), defaultInt(intValue(previous.get("durationSeconds"), null), 3)));
        putPromptValue(shot, "startTime", firstNonBlank(previous.get("endTime"), previous.get("startTime")));
        putPromptValue(shot, "endTime", firstNonBlank(next.get("startTime"), next.get("endTime")));
        putPromptValue(shot, "shotType", firstNonBlank(previous.get("shotType"), next.get("shotType"), "Bridge Shot"));
        putPromptValue(shot, "cameraAngle", firstNonBlank(previous.get("cameraAngle"), next.get("cameraAngle"), "Eye Level"));
        putPromptValue(shot, "cameraMovement", firstNonBlank(previous.get("cameraMovement"), next.get("cameraMovement"), "Static"));
        putPromptValue(shot, "lensSuggestion", firstNonBlank(previous.get("lensSuggestion"), next.get("lensSuggestion"), "Mobile 1x Wide"));
        putPromptValue(shot, "fps", firstNonBlank(previous.get("fps"), next.get("fps"), nested(previous, "cinematicExecution", "recommendedFPS"), nested(next, "cinematicExecution", "recommendedFPS")));
        putPromptValue(shot, "composition", firstNonBlank(previous.get("composition"), next.get("composition"), "center-safe insert that bridges the surrounding shots"));
        putPromptValue(shot, "environment", firstNonBlank(previous.get("environment"), previous.get("setDesign"), next.get("environment"), next.get("setDesign")));
        putPromptValue(shot, "lighting", firstNonBlank(previous.get("lighting"), next.get("lighting")));
        putPromptValue(shot, "textOverlay", firstNonBlank(previous.get("textOverlay"), next.get("textOverlay")));
        putPromptValue(shot, "transition", firstNonBlank(previous.get("transition"), next.get("transition"), "clean cut"));
        putPromptValue(shot, "creatorDirection", firstNonBlank(previous.get("creatorDirection"), next.get("creatorDirection"), "Perform naturally and keep continuity with adjacent shots."));
        putPromptValue(shot, "retentionGoal", "make the inserted beat feel seamless in the existing sequence");
        putPromptValue(shot, "dialogue", "No new dialogue unless the user requested it. Preserve continuity with adjacent dialogue.");
        Map<String, Object> guide = new LinkedHashMap<>();
        guide.put("howToShoot", List.of("Match wardrobe, eyeline, distance, and lighting motivation from the adjacent shots.", request.instruction()));
        guide.put("whyThisWorks", "Adds a timeline beat while preserving continuity.");
        shot.put("rookieFriendlyGuide", guide);
        return shot;
    }

    private Map<String, Object> buildInsertedStoryboardTag(
            Map<String, Object> insertedShot,
            String instruction,
            Map<String, Object> continuityContext
    ) {
        Map<String, Object> tag = new LinkedHashMap<>();
        putPromptValue(tag, "shotNumber", insertedShot.get("shotNumber"));
        putPromptValue(tag, "shotTitle", insertedShot.get("title"));
        putPromptValue(tag, "narrativeBeatSummary", instruction);
        putPromptValue(tag, "action", instruction);
        putPromptValue(tag, "compositionSummary", insertedShot.get("composition"));
        putPromptValue(tag, "environment", insertedShot.get("environment"));
        putPromptValue(tag, "setDesign", insertedShot.get("environment"));
        putPromptValue(tag, "cameraAngle", insertedShot.get("cameraAngle"));
        putPromptValue(tag, "cameraMovement", insertedShot.get("cameraMovement"));
        putPromptValue(tag, "shotType", insertedShot.get("shotType"));
        putPromptValue(tag, "lensSuggestion", insertedShot.get("lensSuggestion"));
        putPromptValue(tag, "directorNote", "AI inserted shot. Match adjacent continuity exactly: " + truncatePromptText(toJson(continuityContext), 700));
        putPromptValue(tag, "targetFocalPoint", "seamless timeline insert");
        return tag;
    }

    private void shiftStoryboardScenesForInsert(UUID storyboardId, int firstShotNumber) {
        if (storyboardId == null || firstShotNumber <= 0) {
            return;
        }
        jdbcTemplate.update(
                """
                update creator_storyboard_scenes
                   set shot_number = shot_number + 1000,
                       metadata = metadata || jsonb_build_object('timelineShiftedAt', now()::text),
                       updated_at = now()
                 where storyboard_id = ?
                   and shot_number >= ?
                """,
                storyboardId,
                firstShotNumber
        );
        jdbcTemplate.update(
                """
                update creator_storyboard_scenes
                   set shot_number = shot_number - 999,
                       updated_at = now()
                 where storyboard_id = ?
                   and shot_number >= ?
                """,
                storyboardId,
                firstShotNumber + 1000
        );
    }

    private int maxShotNumber(List<Map<String, Object>> shots) {
        int max = 0;
        for (int index = 0; index < (shots == null ? 0 : shots.size()); index++) {
            max = Math.max(max, intValue(shots.get(index).get("shotNumber"), index + 1));
        }
        return max;
    }

    private int maxStoryboardShotNumber(UUID storyboardId) {
        if (storyboardId == null) {
            return 0;
        }
        Integer value = jdbcTemplate.queryForObject(
                "select coalesce(max(shot_number), 0) from creator_storyboard_scenes where storyboard_id = ?",
                Integer.class,
                storyboardId
        );
        return defaultInt(value, 0);
    }

    private void putPromptValue(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank()) {
            return;
        }
        String text = promptText(value);
        if (!text.isBlank()) {
            target.put(key, value);
        }
    }

    private List<Map<String, Object>> scriptShots(CreatorScript script) {
        if (script.getShots() != null && !script.getShots().isEmpty()) {
            return script.getShots();
        }
        Object payloadShots = script.getScriptPayload() == null ? null : script.getScriptPayload().get("shots");
        if (payloadShots instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {
                    }))
                    .toList();
        }
        return List.of();
    }

    private ProductionPlanTagService.VideoModelCapability storyboardVideoModelCapability(GenerateStoryboardRequest request) {
        String provider = normalizeVideoProviderForCapability(request == null ? null : request.videoProvider());
        String model = defaultString(request == null ? null : request.videoModel(), "");
        int maxClipSeconds = modelCapabilityMaxClipSeconds(provider, model, request == null ? null : request.maxClipSeconds());
        return new ProductionPlanTagService.VideoModelCapability(provider, model, maxClipSeconds);
    }

    private boolean hasStoryboardVideoCapability(GenerateStoryboardRequest request) {
        return request != null
                && (!defaultString(request.videoProvider(), "").isBlank()
                || !defaultString(request.videoModel(), "").isBlank()
                || request.maxClipSeconds() != null);
    }

    private Map<String, Object> videoModelCapabilityMap(ProductionPlanTagService.VideoModelCapability capability) {
        ProductionPlanTagService.VideoModelCapability resolved = capability == null
                ? storyboardVideoModelCapability(null)
                : new ProductionPlanTagService.VideoModelCapability(
                        normalizeVideoProviderForCapability(capability.videoProvider()),
                        defaultString(capability.videoModel(), ""),
                        modelCapabilityMaxClipSeconds(capability.videoProvider(), capability.videoModel(), capability.maxClipSeconds())
                );
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("videoProvider", resolved.videoProvider());
        map.put("videoModel", resolved.videoModel());
        map.put("maxClipSeconds", resolved.maxClipSeconds());
        map.put("maxDialogueSecondsPerShot", Math.max(1, resolved.maxClipSeconds() - 1));
        map.put("dialogueTimingPolicy", dialogueTimingPolicy(resolved.maxClipSeconds()));
        return map;
    }

    private int modelCapabilityMaxClipSeconds(String provider, String model, Integer requestedMaxClipSeconds) {
        String normalizedProvider = normalizeVideoProviderForCapability(provider);
        int providerMax = defaultMaxClipSecondsForCapability(normalizedProvider, model);
        int requested = requestedMaxClipSeconds == null ? providerMax : intValue(requestedMaxClipSeconds, providerMax);
        if (!"google_veo".equals(normalizedProvider) && requested >= 20) {
            providerMax = Math.max(providerMax, Math.min(requested, 20));
        }
        return Math.max(1, Math.min(providerMax, requested));
    }

    private int defaultMaxClipSecondsForCapability(String provider, String model) {
        String normalizedProvider = normalizeVideoProviderForCapability(provider);
        String normalizedModel = defaultString(model, "").toLowerCase(Locale.ROOT).replace('-', '_');
        if ("google_veo".equals(normalizedProvider)) {
            return 8;
        }
        if (normalizedModel.contains("20") || normalizedModel.contains("twenty") || normalizedModel.contains("long")) {
            return 20;
        }
        if ("gemini_omni".equals(normalizedProvider)) {
            return 10;
        }
        return 15;
    }

    private String normalizeVideoProviderForCapability(String provider) {
        String normalized = defaultString(provider, "seedance")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .trim();
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
        if (normalized.equals("seed_dance") || normalized.equals("byteplus_seedance") || normalized.equals("volcengine_seedance")) {
            return "seedance";
        }
        return normalized.isBlank() ? "seedance" : normalized;
    }

    private String dialogueTimingPolicy(int maxClipSeconds) {
        int dialogueSeconds = Math.max(1, maxClipSeconds - 1);
        return "Fit complete spoken dialogue inside "
                + maxClipSeconds
                + " seconds per storyboard shot; keep spoken line budget near "
                + dialogueSeconds
                + " seconds and split longer dialogue into consecutive parts without paraphrasing.";
    }

    private List<Map<String, Object>> storyboardShotsForModelCapability(List<Map<String, Object>> sourceShots, int maxClipSeconds) {
        if (sourceShots == null || sourceShots.isEmpty()) {
            return List.of();
        }
        int safeMaxClipSeconds = Math.max(1, maxClipSeconds);
        int maxWordsPerPart = Math.max(6, (int) Math.floor(Math.max(1, safeMaxClipSeconds - 1) * 2.4d));
        List<Map<String, Object>> result = new ArrayList<>();
        int runningStart = 0;
        for (Map<String, Object> rawShot : sourceShots) {
            Map<String, Object> shot = rawShot == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawShot);
            String dialogue = storyboardDialogueText(shot);
            List<String> chunks = dialogueChunks(dialogue, maxWordsPerPart);
            if (chunks.size() <= 1 || estimatedDialogueSeconds(dialogue) <= safeMaxClipSeconds) {
                Map<String, Object> normalized = storyboardCapabilityShot(shot, result.size() + 1, runningStart, null, 1, 1, safeMaxClipSeconds);
                result.add(normalized);
                runningStart += intValue(normalized.get("durationSeconds"), safeMaxClipSeconds);
                continue;
            }
            int sourceShotNumber = intValue(firstNonNull(shot.get("shotNumber"), shot.get("shot_number")), result.size() + 1);
            for (int index = 0; index < chunks.size(); index++) {
                Map<String, Object> splitShot = new LinkedHashMap<>(shot);
                String chunk = chunks.get(index);
                splitShot.put("voiceOver", chunk);
                splitShot.put("voiceover", chunk);
                splitShot.put("dialogueScript", chunk);
                splitShot.put("exactDialogue", chunk);
                splitShot.put("dialogue", Map.of("line", chunk));
                splitShot.put("sourceShotNumber", sourceShotNumber);
                splitShot.put("dialoguePart", index + 1);
                splitShot.put("dialoguePartCount", chunks.size());
                splitShot.put("title", defaultString(shot.get("title"), "Shot " + sourceShotNumber) + " - Part " + (index + 1));
                splitShot.put("durationSeconds", Math.max(1, Math.min(safeMaxClipSeconds, estimatedDialogueSeconds(chunk))));
                Map<String, Object> normalized = storyboardCapabilityShot(splitShot, result.size() + 1, runningStart, chunk, index + 1, chunks.size(), safeMaxClipSeconds);
                result.add(normalized);
                runningStart += intValue(normalized.get("durationSeconds"), safeMaxClipSeconds);
            }
        }
        return result;
    }

    private Map<String, Object> storyboardCapabilityShot(
            Map<String, Object> shot,
            int shotNumber,
            int runningStart,
            String dialogueChunk,
            int dialoguePart,
            int dialoguePartCount,
            int maxClipSeconds
    ) {
        Map<String, Object> result = new LinkedHashMap<>(shot == null ? Map.of() : shot);
        int durationSeconds = Math.max(1, Math.min(
                maxClipSeconds,
                Math.max(
                        intValue(firstNonNull(result.get("durationSeconds"), result.get("duration_seconds")), maxClipSeconds),
                        estimatedDialogueSeconds(defaultString(dialogueChunk, storyboardDialogueText(result)))
                )
        ));
        result.put("shotNumber", shotNumber);
        result.put("sceneNumber", shotNumber);
        result.put("durationSeconds", durationSeconds);
        result.put("startSeconds", runningStart);
        result.put("endSeconds", runningStart + durationSeconds);
        result.put("startTime", runningStart);
        result.put("endTime", runningStart + durationSeconds);
        result.put("maxClipSeconds", maxClipSeconds);
        result.put("maxDialogueSecondsPerShot", Math.max(1, maxClipSeconds - 1));
        result.put("dialogueTimingPolicy", dialogueTimingPolicy(maxClipSeconds));
        if (dialoguePartCount > 1) {
            result.put("dialoguePart", dialoguePart);
            result.put("dialoguePartCount", dialoguePartCount);
            result.put("storyboardSegmentation", "model_duration_capability");
        }
        return result;
    }

    private String storyboardDialogueText(Map<String, Object> shot) {
        if (shot == null || shot.isEmpty()) {
            return "";
        }
        String explicit = defaultString(firstNonNull(
                firstNonNull(shot.get("dialogueScript"), shot.get("exactDialogue")),
                firstNonNull(shot.get("voiceOver"), shot.get("voiceover"))
        ), "");
        if (!explicit.isBlank()) {
            return normalizeDialogueText(explicit);
        }
        String dialogue = normalizeDialogueText(plainDialogueText(shot.get("dialogue")));
        if (!dialogue.isBlank()) {
            return dialogue;
        }
        return normalizeDialogueText(defaultString(firstNonNull(shot.get("caption"), shot.get("textOverlay")), ""));
    }

    private String plainDialogueText(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::plainDialogueText)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = mapValue(rawMap);
            String direct = defaultString(firstNonNull(
                    firstNonNull(map.get("text"), map.get("line")),
                    firstNonNull(map.get("voiceOver"), map.get("voiceover"))
            ), "");
            if (!direct.isBlank()) {
                return direct;
            }
            return map.values().stream()
                    .map(this::plainDialogueText)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        return defaultString(value, "");
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

    private String normalizeDialogueText(String value) {
        return defaultString(value, "").replaceAll("\\s+", " ").trim();
    }

    private int estimatedDialogueSeconds(String dialogue) {
        String text = normalizeDialogueText(dialogue);
        if (text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.split("\\s+").length / 2.4d) + 1);
    }

    private Map<String, Object> storyboardMetadata(CreatorScript script, UUID generationJobId, String screenType, RenderSize renderSize) {
        return storyboardMetadata(script, generationJobId, screenType, renderSize, null);
    }

    private Map<String, Object> storyboardMetadata(
            CreatorScript script,
            UUID generationJobId,
            String screenType,
            RenderSize renderSize,
            ProductionPlanTagService.VideoModelCapability videoModelCapability
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("generationJobId", generationJobId.toString());
        metadata.put("screenType", screenType);
        metadata.put("renderWidth", renderSize.width());
        metadata.put("renderHeight", renderSize.height());
        metadata.put("videoModelCapability", videoModelCapabilityMap(videoModelCapability));
        metadata.put("imageProvider", properties.getAi().isStoryboardImageGenerationEnabled() ? "gemini" : "local");
        metadata.put("imageModel", properties.getAi().isStoryboardImageGenerationEnabled() ? properties.getAi().getGeminiImageModel() : "local_storyboard_sketch_v1");
        metadata.put("renderer", properties.getAi().isStoryboardImageGenerationEnabled() ? "gemini_image_model" : "local_storyboard_sketch_v1");
        return metadata;
    }

    private Map<String, Object> assetMetadata(
            CreatorScript script,
            UUID storyboardId,
            String shotId,
            int shotNumber,
            String imageKind,
            String screenType,
            RenderSize size,
            Duration ttl,
            Map<String, Object> imageMetadata
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("screenplayId", script.getId().toString());
        metadata.put("storyboardId", storyboardId.toString());
        metadata.put("shotId", shotId);
        metadata.put("shotNumber", shotNumber);
        metadata.put("imageKind", imageKind);
        metadata.put("screenType", screenType);
        metadata.put("renderWidth", size.width());
        metadata.put("renderHeight", size.height());
        metadata.put("signedUrlTtlSeconds", ttl.toSeconds());
        metadata.put("signedUrlGeneratedAt", OffsetDateTime.now().toString());
        metadata.put("imageGeneration", imageMetadata == null ? Map.of() : imageMetadata);
        return metadata;
    }

    private void collectImageUsage(
            String promptType,
            Map<String, Object> imageMetadata,
            CreatorScript script,
            UUID generationJobId,
            List<Map<String, Object>> costMetadataItems,
            String description
    ) {
        Map<String, Object> costMetadata = mapValue(imageMetadata == null ? null : imageMetadata.get("costMetadata"));
        if (costMetadata.isEmpty()) {
            return;
        }
        String provider = defaultString(firstNonBlank(costMetadata.get("provider"), imageMetadata.get("provider")), promptType);
        String model = defaultString(firstNonBlank(costMetadata.get("model"), imageMetadata.get("model")), "");
        creatorAiService.publishProviderUsageDebit(
                promptType,
                provider,
                model,
                costMetadata,
                new CreatorAiService.AiUsageContext(
                        script.getTenantId(),
                        script.getUserId(),
                        script.getProjectId(),
                        generationJobId,
                        null
                ),
                description
        );
        if (costMetadataItems != null) {
            Map<String, Object> item = new LinkedHashMap<>(costMetadata);
            item.putIfAbsent("promptType", promptType);
            costMetadataItems.add(item);
        }
    }

    private Map<String, Object> aggregateImageCostMetadata(List<Map<String, Object>> costMetadataItems) {
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
            totalCost += doubleValue(item.get("totalCost"), 0.0);
            billableTotalCost += doubleValue(firstNonNull(
                    item.get("billableTotalCost"),
                    firstNonNull(item.get("customerTotalCost"), item.get("totalCost"))
            ), 0.0);
            if (currency.isBlank()) {
                currency = defaultString(item.get("currency"), "");
            }
            if (provider.isBlank()) {
                provider = defaultString(item.get("provider"), "");
            }
            if (model.isBlank()) {
                model = defaultString(item.get("model"), "");
            }
        }
        if (totalCost <= 0.0) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("totalCost", totalCost);
        aggregate.put("actualTotalCost", totalCost);
        aggregate.put("billableTotalCost", billableTotalCost > 0.0 ? billableTotalCost : totalCost);
        aggregate.put("customerTotalCost", billableTotalCost > 0.0 ? billableTotalCost : totalCost);
        aggregate.put("currency", currency);
        aggregate.put("provider", provider);
        aggregate.put("model", model);
        aggregate.put("rateUnit", "AGGREGATED_IMAGE_PROVIDER_USAGE");
        aggregate.put("items", costMetadataItems);
        return aggregate;
    }

    private void linkProjectSelectedStoryboard(CreatorStoryboard storyboard) {
        if (storyboard.getProjectId() == null) {
            return;
        }
        jdbcTemplate.update(
                """
                update creator_projects
                   set selected_storyboard_id = ?,
                       status = 'STORYBOARD_GENERATED',
                       updated_at = now()
                 where id = ?
                   and tenant_id = ?
                   and user_id = ?
                """,
                storyboard.getId(),
                storyboard.getProjectId(),
                storyboard.getTenantId(),
                storyboard.getUserId()
        );
    }

    private String objectKey(CreatorScript script, UUID storyboardId, String shotId) {
        return objectKey(script, storyboardId, shotId, "storyboard");
    }

    private String objectKey(CreatorScript script, UUID storyboardId, String shotId, String imageKind) {
        String projectId = script.getProjectId() == null ? "no-project" : script.getProjectId().toString();
        String scriptId = script.getId() == null ? "no-script" : script.getId().toString();
        String screenplayId = scriptId;
        return "%s/%s/%s/%s/%s-%s-each.jpg".formatted(
                sanitizeKeyPart(script.getTenantId()),
                sanitizeKeyPart(projectId),
                sanitizeKeyPart(scriptId),
                sanitizeKeyPart(screenplayId),
                sanitizeKeyPart(defaultString(shotId, storyboardId == null ? "shot" : storyboardId.toString())),
                sanitizeKeyPart(assetKeyType(imageKind))
        );
    }

    private String assetKeyType(String imageKind) {
        String value = defaultString(imageKind, "storyboard").toLowerCase(Locale.ROOT);
        if (value.contains("production") || value.contains("video_anchor") || value.contains("image_anchor")) {
            return "production";
        }
        if (value.contains("light")) {
            return "lighting";
        }
        if (value.contains("camera") || value.equals("dp")) {
            return "dp";
        }
        return "storyboard";
    }

    private String sanitizeKeyPart(String value) {
        return defaultString(value, "unknown").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private RenderSize renderSize(String screenType) {
        if ("horizontal".equals(screenType)) {
            return new RenderSize(1920, 1080);
        }
        return new RenderSize(1080, 1920);
    }

    private Duration signedUrlTtl(Long requestedSeconds) {
        long seconds = requestedSeconds == null ? properties.getStorage().getSignedUrlTtlSeconds() : requestedSeconds;
        seconds = Math.max(300, Math.min(604800, seconds));
        return Duration.ofSeconds(seconds);
    }

    private String normalizeScreenType(String requestedScreenType) {
        String value = defaultString(requestedScreenType, "vertical").trim().toLowerCase(Locale.ROOT);
        if (value.contains("horizontal") || value.contains("landscape") || value.contains("16:9")) {
            return "horizontal";
        }
        return "vertical";
    }

    private int totalDuration(List<Map<String, Object>> shots) {
        return shots.stream()
                .mapToInt(shot -> defaultInt(intValue(shot.get("durationSeconds"), null), 0))
                .sum();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<Map<String, Object>>() {
            });
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> valueMap(Object value) {
        if (value instanceof Map<?, ?>) {
            return mapValue(value);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (value != null && !String.valueOf(value).isBlank()) {
            map.put("value", String.valueOf(value));
        }
        return map;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> map, String parent, String child) {
        Object value = map.get(parent);
        if (value instanceof Map<?, ?> nestedMap) {
            return ((Map<String, Object>) nestedMap).get(child);
        }
        return null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target != null && value != null) {
            target.put(key, value);
        }
    }

    private void recordGeneratedPlanAsset(
            CreatorScriptShotPlan plan,
            String imageKind,
            CreatorAsset asset,
            String signedUrl,
            String generationPrompt
    ) {
        if (plan == null || asset == null) return;
        String completedAt = OffsetDateTime.now().toString();
        Map<String, Object> generatedAsset = new LinkedHashMap<>();
        generatedAsset.put("assetId", asset.getId() == null ? "" : asset.getId().toString());
        generatedAsset.put("assetType", asset.getAssetType());
        generatedAsset.put("bucket", asset.getBucket());
        generatedAsset.put("objectKey", asset.getObjectKey());
        generatedAsset.put("contentType", asset.getContentType());
        generatedAsset.put("signedUrl", defaultString(signedUrl, asset.getPublicUrl()));
        generatedAsset.put("generationPrompt", defaultString(generationPrompt, ""));
        generatedAsset.put("completedAt", completedAt);

        Map<String, Object> input = new LinkedHashMap<>(
                plan.getInputPayload() == null ? Map.of() : plan.getInputPayload()
        );
        Map<String, Object> generatedAssets = new LinkedHashMap<>(mapValue(input.get("generatedPlanningAssets")));
        generatedAssets.put(imageKind, generatedAsset);
        input.put("generatedPlanningAssets", generatedAssets);
        input.put("lastGeneratedImageKind", imageKind);
        input.put("lastGeneratedAt", completedAt);

        if ("storyboard".equals(imageKind)) {
            Map<String, Object> storyboard = new LinkedHashMap<>(
                    plan.getStoryboardTag() == null ? Map.of() : plan.getStoryboardTag()
            );
            storyboard.put("generatedImage", generatedAsset);
            storyboard.put("generatedImageAssetId", generatedAsset.get("assetId"));
            storyboard.put("generatedImageUrl", generatedAsset.get("signedUrl"));
            storyboard.put("generatedImagePrompt", generatedAsset.get("generationPrompt"));
            storyboard.put("storyboardRegenerationRequired", false);
            storyboard.put("lastRegeneratedAt", completedAt);
            plan.setStoryboardTag(storyboard);
            input.put("storyboardRegenerationRequired", false);
        } else if ("production".equals(imageKind)) {
            Map<String, Object> storyboard = new LinkedHashMap<>(
                    plan.getStoryboardTag() == null ? Map.of() : plan.getStoryboardTag()
            );
            storyboard.put("generatedProductFrame", generatedAsset);
            storyboard.put("generatedProductFrameAssetId", generatedAsset.get("assetId"));
            storyboard.put("generatedProductFrameUrl", generatedAsset.get("signedUrl"));
            storyboard.put("generatedProductFramePrompt", generatedAsset.get("generationPrompt"));
            storyboard.put("productFrameRegenerationRequired", false);
            storyboard.put("lastProductFrameRegeneratedAt", completedAt);
            plan.setStoryboardTag(storyboard);
            input.put("productFrameRegenerationRequired", false);
        } else if ("lighting".equals(imageKind)) {
            Map<String, Object> lighting = new LinkedHashMap<>(
                    plan.getLightingBuildSheetTag() == null ? Map.of() : plan.getLightingBuildSheetTag()
            );
            lighting.put("generatedImage", generatedAsset);
            lighting.put("regenerationRequired", false);
            lighting.put("lastRegeneratedAt", completedAt);
            plan.setLightingBuildSheetTag(lighting);
        } else if ("dp".equals(imageKind)) {
            Map<String, Object> camera = new LinkedHashMap<>(
                    plan.getCameraPlanSheetTag() == null ? Map.of() : plan.getCameraPlanSheetTag()
            );
            camera.put("generatedImage", generatedAsset);
            camera.put("regenerationRequired", false);
            camera.put("lastRegeneratedAt", completedAt);
            plan.setCameraPlanSheetTag(camera);
        }

        boolean storyboardPending = Boolean.TRUE.equals(input.get("storyboardRegenerationRequired"));
        boolean productFramePending = Boolean.TRUE.equals(input.get("productFrameRegenerationRequired"));
        input.put("regenerationRequired", storyboardPending || productFramePending);
        plan.setInputPayload(input);
        if (("storyboard".equals(imageKind) || "production".equals(imageKind))
                && !storyboardPending
                && !productFramePending) {
            plan.setStatus("READY");
        }
        shotPlanRepository.save(plan);
    }

    private String dialogueLine(Map<String, Object> shot) {
        String voiceOver = stringValue(shot.get("voiceOver"));
        if (!voiceOver.isBlank()) {
            return "VO: " + voiceOver;
        }
        Map<String, Object> dialogue = mapValue(shot.get("dialogue"));
        if (dialogue.isEmpty()) {
            return "";
        }
        return dialogue.entrySet().stream()
                .map(entry -> entry.getKey() + ": \"" + entry.getValue() + "\"")
                .findFirst()
                .orElse("");
    }

    private String beginnerTip(Map<String, Object> shot) {
        Map<String, Object> guide = mapValue(shot.get("rookieFriendlyGuide"));
        Object howToShoot = guide.get("howToShoot");
        if (howToShoot instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return defaultString(shot.get("creatorDirection"), "keep acting natural");
    }

    private String firstListValue(Object value, String fallback) {
        List<String> values = stringList(value);
        return values.isEmpty() ? fallback : values.get(0);
    }

    private String storyboardSoundNote(Map<String, Object> shot) {
        String ambient = defaultString(firstNonNull(
                shot.get("ambientBedDescription"),
                soundLayerDescription(shot.get("soundDesign"), "ambient_bed")
        ), "");
        String sync = defaultString(firstNonNull(
                shot.get("syncHitDescription"),
                soundLayerDescription(shot.get("soundDesign"), "sync_hit")
        ), "");
        if (!ambient.isBlank() && !sync.isBlank()) {
            return "Ambient: " + ambient + "; Hit: " + sync;
        }
        if (!ambient.isBlank()) {
            return ambient;
        }
        return firstListValue(shot.get("soundDesign"), "room ambience");
    }

    private String backgroundMusicNote(Map<String, Object> shot) {
        Map<String, Object> cue = mapValue(shot.get("backgroundMusicCue"));
        String mood = defaultString(cue.get("musicMood"), "");
        String cueType = defaultString(cue.get("cueType"), "");
        if (!mood.isBlank() && !cueType.isBlank()) {
            return cueType + ": " + mood;
        }
        return defaultString(shot.get("backgroundMusicCue"), "none");
    }

    private String soundLayerDescription(Object value, String layerType) {
        if (!(value instanceof List<?> list)) {
            return "";
        }
        for (Object item : list) {
            Map<String, Object> map = mapValue(item);
            if (layerType.equalsIgnoreCase(stringValue(map.get("layerType")))) {
                return stringValue(map.get("description"));
            }
        }
        return "";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> toMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
        });
    }

    private String truncatePromptText(Object value, int maxLength) {
        String text = defaultString(value, "").replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private Map<String, Object> compactImageContext(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null || keys.length == 0) {
            return Map.of();
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String key : keys) {
            if (key != null && source.containsKey(key) && source.get(key) != null) {
                selected.put(key, source.get(key));
            }
        }
        return selected;
    }

    private Map<String, Object> globalImageContinuityContext(Map<String, Object> screenplay) {
        Map<String, Object> safeScreenplay = screenplay == null ? Map.of() : screenplay;
        Map<String, Object> context = new LinkedHashMap<>(compactImageContext(
                safeScreenplay,
                "projectTitle", "brandName", "productName", "productCategory", "inferredTone", "emotionalArc",
                "creativeDirection", "dialogueLanguage", "contentRules", "typographySystem", "backgroundMusicPlan"
        ));
        Map<String, Object> product = mapValue(firstNonNull(
                safeScreenplay.get("productIntelligence"),
                safeScreenplay.get("productIntelligenceBrief")
        ));
        if (product.isEmpty()) {
            Map<String, Object> creatorContext = mapValue(safeScreenplay.get("creatorContext"));
            product = mapValue(firstNonNull(
                    creatorContext.get("productIntelligenceBrief"),
                    creatorContext.get("productIntelligence")
            ));
        }
        Map<String, Object> productIdentity = compactImageContext(
                product,
                "brandName", "productName", "name", "title", "category", "variant", "flavour", "flavor",
                "productDescription", "visualDescription", "packagingDescription", "packagingDetails", "labelCopy",
                "logoDescription", "approvedClaims", "claims", "ingredients", "colors", "materials", "dimensions",
                "identityLocks", "negativeConstraints"
        );
        Map<String, Object> productUnderstanding = compactImageContext(
                mapValue(product.get("productUnderstanding")),
                "brandName", "productName", "name", "variant", "flavour", "flavor", "visualDescription",
                "packagingDescription", "packagingDetails", "labelCopy", "logoDescription", "approvedClaims",
                "claims", "colors", "materials", "dimensions", "identityLocks", "negativeConstraints"
        );
        if (!productUnderstanding.isEmpty()) {
            productIdentity = new LinkedHashMap<>(productIdentity);
            productIdentity.put("productUnderstanding", productUnderstanding);
        }
        if (!productIdentity.isEmpty()) {
            context.put("productIdentity", productIdentity);
        }
        Map<String, Object> directorBlueprint = videoDirectorBlueprint(safeScreenplay);
        Map<String, Object> standards = compactImageContext(
                directorBlueprint,
                "mode", "conceptTitle", "directingPrinciple", "openingRule", "revealArc", "productIdentityPolicy",
                "cameraPhilosophy", "lightingPhilosophy", "masteringResolution", "captureStandard",
                "cameraDepartmentStandard", "lightingDepartmentStandard", "directionStandard"
        );
        if (!standards.isEmpty()) {
            context.put("directorStandards", standards);
        }
        context.put("continuityPolicy", List.of(
                "Preserve exact approved product and brand identity across every shot.",
                "A reference marked EXACT_SOURCE is used faithfully; an inspiration reference supplies only visual essence and never replaces product identity.",
                "Maintain screen direction, product geometry/state, light direction, palette, set geography, typography system, and edit rhythm across shot boundaries."
        ));
        return context;
    }

    private Map<String, Object> adjacentShotImageContinuity(
            Map<String, Object> screenplay,
            Map<String, Object> currentShot,
            int shotNumber
    ) {
        List<Map<String, Object>> shots = imageShotList(screenplay == null ? null : screenplay.get("shots"));
        Map<String, Object> previous = shotByNumber(shots, shotNumber - 1);
        Map<String, Object> next = shotByNumber(shots, shotNumber + 1);
        Map<String, Object> bridge = new LinkedHashMap<>();
        bridge.put("selectedShotNumber", shotNumber);
        if (!previous.isEmpty()) {
            bridge.put("previousShotOutgoingState", boundaryShotPacket(screenplay, previous, shotNumber - 1, false));
        }
        bridge.put("selectedShotBoundary", compactImageContext(
                currentShot,
                "shotNumber", "startTime", "startTimeSeconds", "endTime", "endTimeSeconds", "durationSeconds",
                "transitionIn", "transitionOut", "continuityAnchor", "productState", "screenDirection"
        ));
        if (!next.isEmpty()) {
            bridge.put("nextShotIncomingState", boundaryShotPacket(screenplay, next, shotNumber + 1, true));
        }
        bridge.put("bridgeRule", "Start from the previous outgoing state and finish in the exact state required by the next shot. Use a motivated match through motion, light, texture, shape, focus, reflection, or product position; do not create a continuity reset.");
        return bridge;
    }

    private Map<String, Object> boundaryShotPacket(
            Map<String, Object> screenplay,
            Map<String, Object> shot,
            int shotNumber,
            boolean incoming
    ) {
        Map<String, Object> boundary = new LinkedHashMap<>(compactImageContext(
                shot,
                "shotNumber", "title", "purpose", "action", "visualDirection", "description", "startTime",
                "startTimeSeconds", "endTime", "endTimeSeconds", "camera", "cameraMovement", "composition",
                "lighting", "environment", "productState", "productVisibilityPercent", "screenDirection",
                "transitionIn", "transitionOut", "continuityAnchor", "textOverlay"
        ));
        Map<String, Object> director = resolvedDirectorShot(screenplay, shot, shotNumber);
        Map<String, Object> directorBoundary = new LinkedHashMap<>(compactImageContext(
                director,
                "directorRole", "objective", "openingImage", "endFrame", "visualConcept", "cameraMovement",
                "lighting", "focusBehavior", "productVisibilityPercent", "transitionIn", "transitionOut",
                "continuityAnchor"
        ));
        List<Map<String, Object>> frames = imageShotList(director.get("perSecondFrames"));
        if (!frames.isEmpty()) {
            Map<String, Object> edgeFrame = incoming ? frames.get(0) : frames.get(frames.size() - 1);
            directorBoundary.put(incoming ? "firstFrame" : "lastFrame", compactDirectorFrame(edgeFrame));
        }
        if (!directorBoundary.isEmpty()) {
            boundary.put("directorBoundary", directorBoundary);
        }
        return boundary;
    }

    private Map<String, Object> currentShotDirectorPacket(
            Map<String, Object> screenplay,
            Map<String, Object> shot,
            int shotNumber
    ) {
        Map<String, Object> director = resolvedDirectorShot(screenplay, shot, shotNumber);
        Map<String, Object> packet = new LinkedHashMap<>(director);
        packet.remove("masterVideoPrompt");
        packet.remove("perSecondVideoPrompt");
        packet.remove("shots");
        packet.remove("shotByShot");
        packet.remove("videoDirectorBlueprint");
        packet.putIfAbsent("shotNumber", shotNumber);
        if (!packet.containsKey("generationPrompt")) {
            putPromptValue(packet, "generationPrompt", firstNonBlank(shot.get("videoPrompt"), shot.get("generationPrompt")));
        }
        List<Map<String, Object>> frames = imageShotList(firstNonBlank(
                director.get("perSecondFrames"),
                shot.get("perSecondFrames")
        ));
        if (!frames.isEmpty()) {
            packet.put("perSecondFrames", frames);
        }
        packet.put("executionRule", "Execute every listed field and every per-second beat coherently. Reconcile repeated wording, but do not omit unique direction or invent replacements.");
        return packet;
    }

    private Map<String, Object> shotScopedImageContext(Map<String, Object> shot) {
        if (shot == null || shot.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> packet = new LinkedHashMap<>(shot);
        packet.remove("masterVideoPrompt");
        packet.remove("perSecondVideoPrompt");
        packet.remove("videoDirectorBlueprint");
        packet.remove("videoDirectorPlan");
        packet.remove("allShots");
        packet.remove("shotByShot");
        packet.remove("clientReview");
        return packet;
    }

    private Map<String, Object> compactDirectorFrame(Map<String, Object> frame) {
        return compactImageContext(
                frame,
                "second", "startTimeSeconds", "endTimeSeconds", "frameDescription", "cameraAction",
                "lightingAction", "focusAction", "directorAction", "transitionAction", "soundAction",
                "productVisibilityPercent", "overlayAction", "continuityAnchor", "promptSegment"
        );
    }

    private Map<String, Object> resolvedDirectorShot(
            Map<String, Object> screenplay,
            Map<String, Object> shot,
            int shotNumber
    ) {
        Map<String, Object> embedded = mapValue(shot == null ? null : shot.get("videoDirectorPlan"));
        if (embedded.containsKey("shots") || embedded.containsKey("shotByShot")) {
            Map<String, Object> resolved = imageShotByNumber(
                    firstNonNull(embedded.get("shots"), embedded.get("shotByShot")),
                    shotNumber
            );
            if (!resolved.isEmpty()) return resolved;
        }
        if (!embedded.isEmpty()) {
            return embedded;
        }
        Map<String, Object> blueprint = videoDirectorBlueprint(screenplay);
        return imageShotByNumber(firstNonNull(blueprint.get("shots"), blueprint.get("shotByShot")), shotNumber);
    }

    private Map<String, Object> videoDirectorBlueprint(Map<String, Object> screenplay) {
        Map<String, Object> safeScreenplay = screenplay == null ? Map.of() : screenplay;
        Map<String, Object> blueprint = mapValue(safeScreenplay.get("videoDirectorPlan"));
        if (!blueprint.isEmpty()) return blueprint;
        Map<String, Object> review = mapValue(safeScreenplay.get("clientReview"));
        return mapValue(review.get("videoDirectorPlan"));
    }

    private Map<String, Object> imageShotByNumber(Object value, int shotNumber) {
        return shotByNumber(imageShotList(value), shotNumber);
    }

    private List<Map<String, Object>> imageShotList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(this::mapValue)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private String uppercase(String value) {
        return defaultString(value, "").toUpperCase(Locale.ROOT);
    }

    private String upper(String value) {
        return defaultString(value, "").toUpperCase(Locale.ROOT);
    }

    private String lower(Object value) {
        return defaultString(value, "").toLowerCase(Locale.ROOT);
    }

    private String defaultString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer intValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        String text = stringValue(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private CreatorAsset findAsset(UUID assetId) {
        if (assetId == null) {
            return null;
        }
        return assetRepository.findById(assetId).orElse(null);
    }

    private int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private record RenderSize(int width, int height) {
    }

    private record PreparedStoryboardGeneration(
            CreatorScript script,
            List<Map<String, Object>> shots,
            String screenType,
            RenderSize renderSize,
            Duration signedUrlTtl,
            ProductionPlanTagService.VideoModelCapability videoModelCapability
    ) {
    }

    private record GeneratedStoryboardImage(byte[] bytes, Map<String, Object> metadata) {
    }

    private record GeneratedAsset(CreatorAsset asset, String signedUrl) {
    }

    private static class ShotImageAssets {
        private CreatorAsset storyboard;
        private CreatorAsset production;
        private CreatorAsset lighting;
        private CreatorAsset cameraPlan;
    }
}
