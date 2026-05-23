package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.domain.entity.CreatorStoryboard;
import com.dalai.llama.creator.domain.entity.CreatorStoryboardScene;
import com.dalai.llama.creator.dto.request.GenerateStoryboardRequest;
import com.dalai.llama.creator.dto.response.StoryboardResponse;
import com.dalai.llama.creator.dto.response.StoryboardSceneResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardSceneRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private static final String CONTENT_TYPE_JPEG = "image/jpeg";
    private static final String ASSET_TYPE_STORYBOARD_IMAGE = "STORYBOARD_IMAGE";
    private static final String ASSET_TYPE_LIGHTING_BUILD_SHEET_IMAGE = "LIGHTING_BUILD_SHEET_IMAGE";
    private static final String ASSET_TYPE_CAMERA_PLAN_SHEET_IMAGE = "CAMERA_PLAN_SHEET_IMAGE";

    private final CreatorScriptRepository scriptRepository;
    private final CreatorScriptShotRepository scriptShotRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final CreatorStoryboardRepository storyboardRepository;
    private final CreatorStoryboardSceneRepository sceneRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final StoryboardImageGenerationService storyboardImageGenerationService;
    private final ProductionPlanTagService productionPlanTagService;
    private final GenerationJobService generationJobService;
    private final CreatorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StoryboardService(
            CreatorScriptRepository scriptRepository,
            CreatorScriptShotRepository scriptShotRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            CreatorStoryboardRepository storyboardRepository,
            CreatorStoryboardSceneRepository sceneRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            StoryboardImageGenerationService storyboardImageGenerationService,
            ProductionPlanTagService productionPlanTagService,
            GenerationJobService generationJobService,
            CreatorProperties properties,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.scriptRepository = scriptRepository;
        this.scriptShotRepository = scriptShotRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.storyboardRepository = storyboardRepository;
        this.sceneRepository = sceneRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.storyboardImageGenerationService = storyboardImageGenerationService;
        this.productionPlanTagService = productionPlanTagService;
        this.generationJobService = generationJobService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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

        CreatorStoryboardScene scene = sceneRepository
                .findByStoryboardIdAndShotNumber(storyboard.getId(), shotNumber)
                .orElseGet(() -> sceneRepository.save(toScene(
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
                )));

        CreatorAsset storyboardAsset = findAsset(scene.getImageAssetId());
        CreatorAsset lightingAsset = findAsset(uuidValue(scene.getMetadata().get("lightingImageAssetId")));
        CreatorAsset cameraPlanAsset = findAsset(uuidValue(scene.getMetadata().get("cameraPlanImageAssetId")));
        String storyboardSignedUrl = storyboardAsset == null ? null : storyboardAsset.getPublicUrl();
        String lightingSignedUrl = lightingAsset == null ? null : lightingAsset.getPublicUrl();
        String cameraPlanSignedUrl = cameraPlanAsset == null ? null : cameraPlanAsset.getPublicUrl();

        if ("storyboard".equals(normalizedKind)) {
            String prompt = buildStoryboardPrompt(script.getScriptPayload(), shot, sourceTag, screenType, renderSize);
            GeneratedAsset generatedAsset = generateStoryboardAsset(script, storyboard.getId(), shot, shotNumber, shotId, screenType, renderSize, signedUrlTtl, prompt);
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

        scene.getMetadata().put("storyboardTag", plan.getStoryboardTag());
        scene.getMetadata().put("lightingBuildSheetTag", plan.getLightingBuildSheetTag());
        scene.getMetadata().put("cameraPlanSheetTag", plan.getCameraPlanSheetTag());
        scene = sceneRepository.save(scene);
        linkProjectSelectedStoryboard(storyboard);
        return toResponse(scene, storyboardAsset, storyboardSignedUrl, lightingAsset, lightingSignedUrl, cameraPlanAsset, cameraPlanSignedUrl, plan);
    }

    private StoryboardResponse runPreparedStoryboardGeneration(PreparedStoryboardGeneration prepared, UUID generationJobId) {
        CreatorScript script = prepared.script();
        List<Map<String, Object>> shots = prepared.shots();
        String screenType = prepared.screenType();
        RenderSize renderSize = prepared.renderSize();
        Duration signedUrlTtl = prepared.signedUrlTtl();
        try {
            Map<Integer, CreatorScriptShotPlan> planByShotNumber = loadPlanByShotNumber(script.getId());
            if (planByShotNumber.size() < shots.size()) {
                productionPlanTagService.generateTagsForScript(script, script.getScriptPayload(), shots, ProductionPlanTagService.DEFAULT_STYLE_KEY);
                planByShotNumber = loadPlanByShotNumber(script.getId());
            }
            CreatorStoryboard storyboard = storyboardRepository.save(CreatorStoryboard.builder()
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
                    .metadata(storyboardMetadata(script, generationJobId, screenType, renderSize))
                    .build());

            Map<Integer, String> shotIdByNumber = loadShotIdByNumber(script.getId());
            List<StoryboardSceneResponse> sceneResponses = new ArrayList<>();
            publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, 8, "Storyboard pack created");
            for (int index = 0; index < shots.size(); index++) {
                Map<String, Object> shot = shots.get(index);
                int shotNumber = intValue(shot.get("shotNumber"), index + 1);
                String shotId = defaultString(shotIdByNumber.get(shotNumber), "shot-%04d".formatted(shotNumber));
                CreatorScriptShotPlan plan = planByShotNumber.get(shotNumber);
                String prompt = buildStoryboardPrompt(script.getScriptPayload(), shot, plan == null ? Map.of() : plan.getStoryboardTag(), screenType, renderSize);
                GeneratedStoryboardImage generatedImage = generateStoryboardImage(shot, screenType, renderSize, prompt);
                byte[] imageBytes = generatedImage.bytes();
                String objectKey = objectKey(script, storyboard.getId(), shotId, "storyboard");

                AssetStorageService.StoredObject storedObject = assetStorageService.uploadCreatorAsset(
                        objectKey,
                        imageBytes,
                        CONTENT_TYPE_JPEG,
                        signedUrlTtl
                );

                CreatorAsset asset = assetRepository.save(CreatorAsset.builder()
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

                CreatorStoryboardScene scene = sceneRepository.save(toScene(
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
                    putIfPresent(scene.getMetadata(), "lightingImageAssetId", lightingAsset.getId().toString());
                    scene = sceneRepository.save(scene);
                    replaceSceneResponse(sceneResponses, toResponse(scene, asset, storedObject.signedUrl(), lightingAsset, lightingSignedUrl, cameraPlanAsset, cameraPlanSignedUrl, plan));
                    publishStoryboardProgress(generationJobId, storyboard, script, screenType, renderSize, sceneResponses, progressFor(index, shots.size(), 2), "Lighting sheet ready for shot " + shotNumber);

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

        List<Map<String, Object>> shots = scriptShots(script);
        if (shots.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Final script does not have shots for storyboard generation.");
        }

        String screenType = normalizeScreenType(defaultString(
                request == null ? null : request.screenType(),
                defaultString(script.getScreenType(), stringValue(script.getScriptPayload().get("screenType")))
        ));
        RenderSize renderSize = renderSize(screenType);
        Duration signedUrlTtl = signedUrlTtl(request == null ? null : request.signedUrlTtlSeconds());
        return new PreparedStoryboardGeneration(script, shots, screenType, renderSize, signedUrlTtl);
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
        CreatorStoryboard storyboard = storyboardRepository.save(CreatorStoryboard.builder()
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
            metadata.put("lightingImageAssetId", lightingAssetId == null ? null : lightingAssetId.toString());
            metadata.put("cameraPlanImageAssetId", cameraPlanAssetId == null ? null : cameraPlanAssetId.toString());
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
                signedUrl,
                scene.getSketchPrompt(),
                lightingAsset == null ? null : lightingAsset.getId(),
                lightingAsset == null ? null : lightingAsset.getObjectKey(),
                lightingSignedUrl,
                cameraPlanAsset == null ? null : cameraPlanAsset.getId(),
                cameraPlanAsset == null ? null : cameraPlanAsset.getObjectKey(),
                cameraPlanSignedUrl,
                stringValue(scene.getMetadata().get("screenType")),
                intValue(scene.getMetadata().get("renderWidth"), null),
                intValue(scene.getMetadata().get("renderHeight"), null),
                plan == null ? Map.of() : plan.getStoryboardTag(),
                plan == null ? Map.of() : plan.getLightingBuildSheetTag(),
                plan == null ? Map.of() : plan.getCameraPlanSheetTag()
        );
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
        generationJobService.updateGenerationJobProgress(generationJobId, progress, message, output);
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
            String prompt
    ) {
        GeneratedStoryboardImage generatedImage = generateStoryboardImage(shot, screenType, renderSize, prompt);
        String objectKey = objectKey(script, storyboardId, shotId, "storyboard");
        AssetStorageService.StoredObject storedObject = assetStorageService.uploadCreatorAsset(
                objectKey,
                generatedImage.bytes(),
                CONTENT_TYPE_JPEG,
                signedUrlTtl
        );
        CreatorAsset asset = assetRepository.save(CreatorAsset.builder()
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
        String prompt = buildProductionSheetPrompt(imageKind, tag, screenType, renderSize);
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
        CreatorAsset asset = assetRepository.save(CreatorAsset.builder()
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

    private GeneratedStoryboardImage generateStoryboardImage(Map<String, Object> shot, String screenType, RenderSize size, String prompt) {
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

        StoryboardImageGenerationService.GeneratedImage generatedImage =
                storyboardImageGenerationService.generateStoryboardImage(prompt, screenType);
        return new GeneratedStoryboardImage(
                normalizeToRenderSizeJpeg(generatedImage.bytes(), size),
                generatedImage.metadata()
        );
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

    private String buildStoryboardPrompt(
            Map<String, Object> screenplayJson,
            Map<String, Object> shot,
            Map<String, Object> storyboardTag,
            String screenType,
            RenderSize size
    ) {
        String override = stringValue(storyboardTag == null ? null : storyboardTag.get("imageGenerationPromptOverride"));
        if (!override.isBlank()) {
            return override;
        }
        int shotNumber = intValue(shot.get("shotNumber"), 1);
        String title = defaultString(shot.get("title"), "Storyboard Shot");
        String timestamp = defaultString(shot.get("startTime"), "0:00") + " - " + defaultString(shot.get("endTime"), "0:00");
        String movement = defaultString(firstNonNull(shot.get("cameraMovement"), nested(shot, "cinematicExecution", "cameraStyle")), "STATIC");
        String action = defaultString(shot.get("action"), defaultString(shot.get("primaryActorAction"), "Perform the planned action naturally."));
        String dialogue = dialogueLine(shot);
        String tagJson = storyboardTag == null || storyboardTag.isEmpty() ? "" : "\nStoryboardTag JSON to honor exactly:\n" + toJson(storyboardTag);
        String screenplayContextJson = screenplayJson == null || screenplayJson.isEmpty() ? "" : "\nComplete screenplay JSON context for continuity and style:\n" + toJson(screenplayJson);
        return """
                Professional monochrome pencil storyboard sketch, single cinematic shot only, %s composition, exact %sx%s output.
                Style: grayscale director storyboard, readable production-planning annotations inside the frame, rough hand-drawn sketch, not glossy art, not poster, not collage.
                Header text inside image: SHOT %02d, %s, %s.
                Camera annotations inside image: CAM %s, SHOT TYPE %s, %s FPS, MOVE %s, LENS %s.
                Performance annotations inside image: EXPRESSION %s, EMOTION %s, BODY %s, LIGHT %s, TRANSITION %s.
                Composition note: %s. Environment: %s.
                Main sketch action: %s.
                Subtitle/dialogue inside frame: %s.
                Text overlay inside frame: %s.
                Sound note: %s. Music cue: %s. Beginner tip: %s.
                Draw motion arrows for camera or eye/body movement where useful. Keep all labels mobile-readable.
                %s
                %s
                """.formatted(
                "horizontal".equals(screenType) ? "16:9 horizontal" : "9:16 vertical",
                size.width(),
                size.height(),
                shotNumber,
                truncatePromptText(title, 70),
                truncatePromptText(timestamp, 40),
                truncatePromptText(defaultString(shot.get("cameraAngle"), "planned angle"), 60),
                truncatePromptText(defaultString(shot.get("shotType"), "storyboard shot"), 45),
                truncatePromptText(defaultString(firstNonNull(shot.get("fps"), nested(shot, "cinematicExecution", "recommendedFPS")), "30"), 10),
                truncatePromptText(movement, 35),
                truncatePromptText(defaultString(shot.get("lensSuggestion"), "phone wide"), 35),
                truncatePromptText(defaultString(shot.get("expression"), "natural"), 45),
                truncatePromptText(defaultString(shot.get("emotion"), "clear intent"), 45),
                truncatePromptText(defaultString(shot.get("bodyLanguage"), "natural posture"), 55),
                truncatePromptText(defaultString(shot.get("lighting"), "soft available light"), 55),
                truncatePromptText(defaultString(shot.get("transition"), "hard cut"), 35),
                truncatePromptText(defaultString(shot.get("composition"), "center-safe"), 65),
                truncatePromptText(defaultString(firstNonNull(shot.get("environment"), shot.get("setDesign")), "creator shooting space"), 65),
                truncatePromptText(action, 150),
                truncatePromptText(defaultString(dialogue, "none"), 100),
                truncatePromptText(defaultString(shot.get("textOverlay"), "none"), 55),
                truncatePromptText(storyboardSoundNote(shot), 90),
                truncatePromptText(backgroundMusicNote(shot), 70),
                truncatePromptText(beginnerTip(shot), 75),
                tagJson,
                screenplayContextJson
        );
    }

    private String buildProductionSheetPrompt(String imageKind, Map<String, Object> tag, String screenType, RenderSize size) {
        String override = stringValue(tag == null ? null : tag.get("imageGenerationPromptOverride"));
        if (!override.isBlank()) {
            return override;
        }
        String title = "lighting".equals(imageKind) ? "rookie-executable lighting build sheet" : "shoot-ready camera plan sheet";
        return """
                Professional monochrome production planning sheet, %s, exact %sx%s output, %s composition.
                Render as a clear storyboard-adjacent technical diagram with readable labels, top-down map, perspective sketch, numbered cards, and checklist steps.
                Keep all text large enough for mobile review. Use the supplied JSON exactly; do not invent missing values.
                Source JSON:
                %s
                """.formatted(
                title,
                size.width(),
                size.height(),
                "horizontal".equals(screenType) ? "16:9 horizontal" : "9:16 vertical",
                toJson(tag == null ? Map.of() : tag)
        );
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

    private Map<String, Object> storyboardMetadata(CreatorScript script, UUID generationJobId, String screenType, RenderSize renderSize) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("generationJobId", generationJobId.toString());
        metadata.put("screenType", screenType);
        metadata.put("renderWidth", renderSize.width());
        metadata.put("renderHeight", renderSize.height());
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
            Duration signedUrlTtl
    ) {
    }

    private record GeneratedStoryboardImage(byte[] bytes, Map<String, Object> metadata) {
    }

    private record GeneratedAsset(CreatorAsset asset, String signedUrl) {
    }
}
