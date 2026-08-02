package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorIdea;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorProject;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.dto.request.GenerateProductAdPipelineRequest;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorIdeaRepository;
import com.dalai.llama.creator.repository.CreatorProjectRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductAdPipelineService {

    private static final Logger log = LoggerFactory.getLogger(ProductAdPipelineService.class);
    private static final String JOB_TYPE = "PRODUCT_AD_PIPELINE";
    private static final String ASSET_TYPE_PRODUCT_AD_IMAGE = "PRODUCT_AD_IMAGE";
    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);

    private final ProductAdResearchService productAdResearchService;
    private final StoryboardImageGenerationService storyboardImageGenerationService;
    private final AssetStorageService assetStorageService;
    private final CreatorAssetRepository assetRepository;
    private final CreatorProjectRepository projectRepository;
    private final CreatorIdeaRepository ideaRepository;
    private final CreatorScriptRepository scriptRepository;
    private final GenerationJobService generationJobService;
    private final CreatorAiService creatorAiService;
    private final ScreenplayVideoService screenplayVideoService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TaskExecutor taskExecutor;
    private final WebClient webClient;

    public ProductAdPipelineService(
            ProductAdResearchService productAdResearchService,
            StoryboardImageGenerationService storyboardImageGenerationService,
            AssetStorageService assetStorageService,
            CreatorAssetRepository assetRepository,
            CreatorProjectRepository projectRepository,
            CreatorIdeaRepository ideaRepository,
            CreatorScriptRepository scriptRepository,
            GenerationJobService generationJobService,
            CreatorAiService creatorAiService,
            ScreenplayVideoService screenplayVideoService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor,
            WebClient.Builder webClientBuilder
    ) {
        this.productAdResearchService = productAdResearchService;
        this.storyboardImageGenerationService = storyboardImageGenerationService;
        this.assetStorageService = assetStorageService;
        this.assetRepository = assetRepository;
        this.projectRepository = projectRepository;
        this.ideaRepository = ideaRepository;
        this.scriptRepository = scriptRepository;
        this.generationJobService = generationJobService;
        this.creatorAiService = creatorAiService;
        this.screenplayVideoService = screenplayVideoService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.taskExecutor = taskExecutor;
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    public CreatorGenerationJob startPipeline(
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId
    ) {
        GenerateProductAdPipelineRequest safeRequest = request == null
                ? new GenerateProductAdPipelineRequest(null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null, false, true, true, true, true, null, null, null, null, Map.of(), Map.of(), null)
                : request;
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        Map<String, Object> input = requestToMap(safeRequest);
        input.put("idempotencyKey", firstText(safeRequest.idempotencyKey(), productAdIdempotencyKey(safeRequest)));
        input.put("tenantId", safeTenantId);
        input.put("userId", safeUserId);

        String idempotencyKey = String.valueOf(input.get("idempotencyKey"));
        CreatorGenerationJob activeJob = generationJobService
                .findActiveGenerationJobByIdempotencyKey(safeTenantId, safeUserId, JOB_TYPE, idempotencyKey)
                .orElse(null);
        if (activeJob != null) {
            return activeJob;
        }
        CreatorGenerationJob completedJob = generationJobService
                .findCompletedGenerationJobByIdempotencyKey(safeTenantId, safeUserId, JOB_TYPE, idempotencyKey)
                .orElse(null);
        if (completedJob != null) {
            return completedJob;
        }

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_TYPE,
                safeTenantId,
                safeUserId,
                safeRequest.projectId(),
                input
        );
        taskExecutor.execute(() -> runPipeline(job.getId(), safeRequest, safeTenantId, safeUserId));
        return job;
    }

    public List<Map<String, Object>> listAssets(UUID jobId, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        StringBuilder sql = new StringBuilder("""
                select id,
                       tenant_id,
                       user_id,
                       project_id,
                       locked_idea_id,
                       story_idea_id,
                       script_id,
                       generation_job_id,
                       media_asset_id,
                       asset_kind,
                       status,
                       provider,
                       model,
                       prompt,
                       asset_url,
                       metadata,
                       error_message,
                       created_at,
                       updated_at
                  from creator_product_ad_assets
                 where tenant_id = ?
                   and user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(safeTenantId);
        args.add(safeUserId);
        if (jobId != null) {
            sql.append(" and generation_job_id = ?");
            args.add(jobId);
        }
        sql.append(" order by created_at asc");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", stringValue(rs.getObject("id")));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("userId", rs.getString("user_id"));
            row.put("projectId", stringValue(rs.getObject("project_id")));
            row.put("lockedIdeaId", stringValue(rs.getObject("locked_idea_id")));
            row.put("storyIdeaId", stringValue(rs.getObject("story_idea_id")));
            row.put("scriptId", stringValue(rs.getObject("script_id")));
            row.put("generationJobId", stringValue(rs.getObject("generation_job_id")));
            row.put("mediaAssetId", stringValue(rs.getObject("media_asset_id")));
            row.put("assetKind", rs.getString("asset_kind"));
            row.put("status", rs.getString("status"));
            row.put("provider", rs.getString("provider"));
            row.put("model", rs.getString("model"));
            row.put("prompt", rs.getString("prompt"));
            row.put("assetUrl", rs.getString("asset_url"));
            row.put("metadata", parseJson(rs.getString("metadata")));
            row.put("errorMessage", rs.getString("error_message"));
            row.put("createdAt", stringValue(rs.getObject("created_at")));
            row.put("updatedAt", stringValue(rs.getObject("updated_at")));
            return row;
        }, args.toArray());
    }

    private void runPipeline(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId
    ) {
        if (!generationJobService.claimGenerationJobExecution(jobId, "product-ad-pipeline")) {
            log.info("Skipping duplicate product ad pipeline execution jobId={} tenantId={} userId={}", jobId, tenantId, userId);
            return;
        }
        try {
            generationJobService.updateGenerationJobProgress(jobId, 8, "Extracting product page and campaign evidence");
            Map<String, Object> plan = productAdResearchService.buildProductAdPlan(request, tenantId, userId, jobId);
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    35,
                    "Product intelligence and ad strategy ready",
                    Map.of(
                            "productIntelligence", plan.get("productIntelligence"),
                            "marketResearch", plan.get("marketResearch"),
                            "adConcepts", plan.get("adConcepts"),
                            "message", "Product intelligence and ad strategy ready"
                    )
            );

            ProductAdWorkflow workflow = ensureWorkflowPersistence(jobId, request, tenantId, userId, plan);
            GenerateProductAdPipelineRequest workflowRequest = withWorkflowIds(request, workflow);
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    42,
                    "Saved product ad workflow",
                    Map.of(
                            "savedWorkflow", workflow.toMap(),
                            "message", "Locked idea, story idea, and screenplay are saved for the product ad flow"
                    )
            );

            List<Map<String, Object>> generatedAssets = Boolean.FALSE.equals(workflowRequest.generateImages())
                    ? List.of()
                    : generateImageAssets(jobId, workflowRequest, tenantId, userId, plan);

            List<Map<String, Object>> anchoredShots = attachGeneratedAssetsToShots(plan, generatedAssets);
            syncWorkflowScriptAssets(workflowRequest, plan, anchoredShots, generatedAssets);
            Map<String, Object> videoRequest = buildVideoRequest(workflowRequest, plan, anchoredShots, generatedAssets);
            Map<String, Object> output = new LinkedHashMap<>(plan);
            output.put("savedWorkflow", workflow.toMap());
            output.put("projectId", workflow.projectId() == null ? null : workflow.projectId().toString());
            output.put("lockedIdeaId", workflow.lockedIdeaId().toString());
            output.put("storyIdeaId", workflow.storyIdeaId().toString());
            output.put("scriptId", workflow.scriptId().toString());
            output.put("generatedAssets", generatedAssets);
            output.put("shotPlan", anchoredShots);
            output.put("videoGenerationRequest", videoRequest);
            output.put("workflow", Map.of(
                    "step1", "product_url_or_name_to_product_intelligence",
                    "step2", "marketing_strategy_and_three_concepts",
                    "step3", "image_prompts_and_generated_product_image_anchors",
                    "step4", "image_to_video_per_shot_using_existing screenplay video run",
                    "step5", "final_render_and_editor_submission"
            ));

            if (workflowRequest.scriptId() != null && !Boolean.FALSE.equals(workflowRequest.autoPrepareVideoRun())) {
                generationJobService.updateGenerationJobProgress(jobId, 92, "Preparing per-shot video run with product image anchors", output);
                CreatorGenerationJob videoRunJob = screenplayVideoService.startVideoGenerationJob(
                        workflowRequest.scriptId(),
                        videoRequest,
                        tenantId,
                        userId
                );
                output.put("screenplayVideoRunJob", objectMapper.convertValue(
                        generationJobService.toResponse(videoRunJob),
                        new TypeReference<Map<String, Object>>() {
                        }
                ));
            }

            output.put("message", generatedAssets.isEmpty()
                    ? "Product ad plan ready. Image generation was skipped."
                    : "Product ad image anchors generated. Use the prepared video request to generate each shot one by one.");
            generationJobService.completeGenerationJob(jobId, output);
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.error(
                    "Product ad pipeline failed jobId={} tenantId={} userId={} errorType={} errorMessage={}",
                    jobId,
                    tenantId,
                    userId,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex
            );
        }
    }

    private List<Map<String, Object>> generateImageAssets(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            Map<String, Object> plan
    ) {
        List<Map<String, Object>> imagePrompts = mapListValue(plan.get("imagePrompts"));
        if (imagePrompts.isEmpty()) {
            return List.of();
        }
        List<StoryboardImageGenerationService.ReferenceImageInput> referenceImages =
                downloadReferenceImages(referenceImageUrls(request, plan));
        List<Map<String, Object>> generatedAssets = new ArrayList<>();
        int total = imagePrompts.size();
        for (int index = 0; index < total; index++) {
            Map<String, Object> imagePrompt = imagePrompts.get(index);
            String assetKind = slug(firstText(imagePrompt.get("assetKind"), imagePrompt.get("title"), "product_ad_image_" + (index + 1)));
            String prompt = buildImagePrompt(imagePrompt, plan, request, index + 1);
            UUID productAssetRowId = insertProductAdAssetRow(jobId, request, tenantId, userId, assetKind, prompt);
            try {
                generationJobService.updateGenerationJobProgress(
                        jobId,
                        imageProgress(index, total),
                        "Generating product image anchor " + (index + 1) + " of " + total,
                        Map.of("activeImageAssetId", productAssetRowId.toString())
                );
                StoryboardImageGenerationService.GeneratedImage generatedImage = referenceImages.isEmpty()
                        ? storyboardImageGenerationService.generateStoryboardImage(prompt, firstText(request.screenType(), "vertical"))
                        : storyboardImageGenerationService.generateImageFromReferences(prompt, referenceImages, firstText(request.screenType(), "vertical"));
                CreatorAsset mediaAsset = saveGeneratedImageAsset(jobId, request, tenantId, userId, assetKind, prompt, generatedImage, index + 1);
                String signedUrl = assetStorageService.signedUrl(mediaAsset.getBucket(), mediaAsset.getObjectKey(), SIGNED_URL_TTL);
                Map<String, Object> metadata = new LinkedHashMap<>(mediaAsset.getMetadata() == null ? Map.of() : mediaAsset.getMetadata());
                metadata.put("productAdAssetId", productAssetRowId.toString());
                updateProductAdAssetSuccess(productAssetRowId, mediaAsset, signedUrl, metadata);
                publishImageDebit(generatedImage, request, tenantId, userId, jobId, assetKind);

                Map<String, Object> asset = new LinkedHashMap<>();
                asset.put("id", productAssetRowId.toString());
                asset.put("mediaAssetId", mediaAsset.getId().toString());
                asset.put("assetKind", assetKind);
                asset.put("assetType", ASSET_TYPE_PRODUCT_AD_IMAGE);
                asset.put("referenceRole", "product_visual_anchor");
                asset.put("bucket", mediaAsset.getBucket());
                asset.put("objectKey", mediaAsset.getObjectKey());
                asset.put("shotNumber", intValue(firstValue(imagePrompt.get("shotNumber"), imagePrompt.get("sceneNumber")), index + 1));
                asset.put("title", firstText(imagePrompt.get("title"), "Product image " + (index + 1)));
                asset.put("prompt", prompt);
                asset.put("videoMotionPrompt", firstText(imagePrompt.get("videoMotionPrompt"), imagePrompt.get("motionPrompt")));
                asset.put("assetUrl", signedUrl);
                asset.put("signedUrl", signedUrl);
                asset.put("publicUrl", signedUrl);
                asset.put("contentType", mediaAsset.getContentType());
                asset.put("metadata", metadata);
                asset.put("status", "COMPLETED");
                generatedAssets.add(asset);
            } catch (RuntimeException ex) {
                updateProductAdAssetFailure(productAssetRowId, ex);
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("id", productAssetRowId.toString());
                failed.put("assetKind", assetKind);
                failed.put("shotNumber", intValue(firstValue(imagePrompt.get("shotNumber"), imagePrompt.get("sceneNumber")), index + 1));
                failed.put("prompt", prompt);
                failed.put("status", "FAILED");
                failed.put("errorMessage", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                generatedAssets.add(failed);
                log.warn(
                        "Product ad image generation failed jobId={} assetId={} assetKind={} errorType={} errorMessage={}",
                        jobId,
                        productAssetRowId,
                        assetKind,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
            }
        }
        return generatedAssets;
    }

    private ProductAdWorkflow ensureWorkflowPersistence(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            Map<String, Object> plan
    ) {
        UUID projectId = ensureProductProject(request, tenantId, userId, plan);
        CreatorIdea lockedIdea = ensureLockedIdea(jobId, request, tenantId, userId, projectId, plan);
        CreatorIdea storyIdea = ensureStoryIdea(jobId, request, tenantId, userId, projectId, lockedIdea, plan);
        CreatorScript script = ensureProductScript(jobId, request, tenantId, userId, projectId, lockedIdea, storyIdea, plan, mapListValue(plan.get("shotPlan")), List.of());
        return new ProductAdWorkflow(projectId, lockedIdea.getId(), storyIdea.getId(), script.getId());
    }

    private UUID ensureProductProject(
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            Map<String, Object> plan
    ) {
        if (request.projectId() != null) {
            return request.projectId();
        }
        Map<String, Object> product = mapValue(plan.get("productIntelligence"));
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("source", "PRODUCT_AD_AGENT");
        memory.put("productIntelligence", product);
        memory.put("marketResearch", plan.get("marketResearch"));
        memory.put("adConcepts", plan.get("adConcepts"));
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("title", productTitle(request, product));
        preferences.put("briefTitle", productTitle(request, product));
        preferences.put("sourceType", "PRODUCT_AD_AGENT");
        preferences.put("summary", productSummary(request, product, plan));
        preferences.put("categoryCode", firstText(request.categoryCode(), "advertisement"));
        preferences.put("platformCode", firstText(request.platformCode(), "instagram_reels"));
        preferences.put("durationSeconds", intValue(request.durationSeconds(), 60));
        preferences.put("screenType", firstText(request.screenType(), "vertical"));
        preferences.put("productionStyle", "FULL_AI");
        CreatorProject project = projectRepository.save(CreatorProject.builder()
                .tenantId(tenantId)
                .userId(userId)
                .status("ACTIVE")
                .selectedPlatformCode(firstText(request.platformCode(), "instagram_reels"))
                .selectedCategoryCode(firstText(request.categoryCode(), "advertisement"))
                .timeframe("LAST_7_DAYS")
                .countryCode(firstText(mapValue(request.brandContext()).get("countryCode"), "IN"))
                .durationSeconds(intValue(request.durationSeconds(), 60))
                .preferences(preferences)
                .memorySnapshot(memory)
                .build());
        return project.getId();
    }

    private CreatorIdea ensureLockedIdea(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            UUID projectId,
            Map<String, Object> plan
    ) {
        if (request.lockedIdeaId() != null) {
            CreatorIdea existing = ideaRepository.findByIdAndTenantIdAndUserId(request.lockedIdeaId(), tenantId, userId).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        Map<String, Object> product = mapValue(plan.get("productIntelligence"));
        Map<String, Object> context = productWorkflowContext(jobId, request, plan);
        context.put("workflowRole", "LOCKED_PRODUCT_AD_BRIEF");
        CreatorIdea lockedIdea = CreatorIdea.builder()
                .id(request.lockedIdeaId())
                .tenantId(tenantId)
                .userId(userId)
                .projectId(projectId)
                .source("PRODUCT_AD_AGENT")
                .title(productTitle(request, product))
                .summary(productSummary(request, product, plan))
                .script(firstText(plan.get("storyScript"), plan.get("script")))
                .scenes(mapListValue(plan.get("shotPlan")))
                .durationSeconds(intValue(request.durationSeconds(), 60))
                .status("LOCKED")
                .saved(true)
                .generationJobId(jobId)
                .selectionContext(context)
                .lockedAt(OffsetDateTime.now())
                .build();
        return ideaRepository.save(lockedIdea);
    }

    private CreatorIdea ensureStoryIdea(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            UUID projectId,
            CreatorIdea lockedIdea,
            Map<String, Object> plan
    ) {
        if (request.storyIdeaId() != null) {
            CreatorIdea existing = ideaRepository.findByIdAndTenantIdAndUserId(request.storyIdeaId(), tenantId, userId).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        Map<String, Object> context = productWorkflowContext(jobId, request, plan);
        context.put("workflowRole", "PRODUCT_AD_STORY_IDEA");
        context.put("parentLockedIdeaId", lockedIdea.getId().toString());
        context.put("storyScript", firstText(plan.get("storyScript"), plan.get("script")));
        CreatorIdea storyIdea = CreatorIdea.builder()
                .id(request.storyIdeaId())
                .tenantId(tenantId)
                .userId(userId)
                .projectId(projectId)
                .source("AI_FROM_LOCKED_BRIEF")
                .title(firstText(plan.get("title"), lockedIdea.getTitle(), "Product ad concept"))
                .summary(firstText(plan.get("strategySummary"), lockedIdea.getSummary()))
                .script(firstText(plan.get("storyScript"), plan.get("script")))
                .scenes(mapListValue(plan.get("shotPlan")))
                .durationSeconds(intValue(request.durationSeconds(), lockedIdea.getDurationSeconds() == null ? 60 : lockedIdea.getDurationSeconds()))
                .status("SCRIPT_GENERATED")
                .saved(true)
                .generationJobId(jobId)
                .selectionContext(context)
                .build();
        return ideaRepository.save(storyIdea);
    }

    private CreatorScript ensureProductScript(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            UUID projectId,
            CreatorIdea lockedIdea,
            CreatorIdea storyIdea,
            Map<String, Object> plan,
            List<Map<String, Object>> shots,
            List<Map<String, Object>> generatedAssets
    ) {
        if (request.scriptId() != null) {
            CreatorScript existing = scriptRepository.findByIdAndTenantIdAndUserId(request.scriptId(), tenantId, userId).orElse(null);
            if (existing != null) {
                updateProductScript(existing, jobId, request, plan, shots, generatedAssets);
                return scriptRepository.save(existing);
            }
        }
        List<Map<String, Object>> safeShots = shots == null || shots.isEmpty() ? mapListValue(plan.get("shotPlan")) : shots;
        Map<String, Object> payload = productScriptPayload(jobId, request, plan, safeShots, generatedAssets);
        CreatorScript script = CreatorScript.builder()
                .id(request.scriptId())
                .tenantId(tenantId)
                .userId(userId)
                .projectId(projectId)
                .lockedIdeaId(lockedIdea.getId())
                .storyIdeaId(storyIdea.getId())
                .categoryCode(firstText(request.categoryCode(), "advertisement"))
                .durationSeconds(intValue(request.durationSeconds(), 60))
                .formatTier("PRODUCT_AD")
                .actStructure("HOOK_PROOF_OFFER_CTA")
                .budgetTier("AI_PRODUCT_AD")
                .totalShots(safeShots.size())
                .sceneCount(safeShots.size())
                .sequenceCount(1)
                .dialogueLanguage(firstText(mapValue(request.brandContext()).get("dialogueLanguage"), "English"))
                .screenType(firstText(request.screenType(), "vertical"))
                .title(firstText(payload.get("projectTitle"), storyIdea.getTitle(), lockedIdea.getTitle()))
                .scriptText(productScriptText(plan, safeShots))
                .scriptPayload(payload)
                .shots(safeShots)
                .status("GENERATED")
                .build();
        return scriptRepository.save(script);
    }

    private void syncWorkflowScriptAssets(
            GenerateProductAdPipelineRequest request,
            Map<String, Object> plan,
            List<Map<String, Object>> anchoredShots,
            List<Map<String, Object>> generatedAssets
    ) {
        if (request.scriptId() == null) {
            return;
        }
        CreatorScript script = scriptRepository.findById(request.scriptId()).orElse(null);
        if (script == null) {
            return;
        }
        updateProductScript(script, null, request, plan, anchoredShots, generatedAssets);
        scriptRepository.save(script);
        if (request.storyIdeaId() != null) {
            ideaRepository.findById(request.storyIdeaId()).ifPresent(storyIdea -> {
                storyIdea.setScenes(anchoredShots);
                storyIdea.setScript(productScriptText(plan, anchoredShots));
                Map<String, Object> context = new LinkedHashMap<>(storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext());
                context.put("generatedAssets", generatedAssets);
                context.put("scriptId", request.scriptId().toString());
                storyIdea.setSelectionContext(context);
                ideaRepository.save(storyIdea);
            });
        }
    }

    private void updateProductScript(
            CreatorScript script,
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            Map<String, Object> plan,
            List<Map<String, Object>> shots,
            List<Map<String, Object>> generatedAssets
    ) {
        List<Map<String, Object>> safeShots = shots == null || shots.isEmpty() ? mapListValue(plan.get("shotPlan")) : shots;
        Map<String, Object> payload = productScriptPayload(jobId, request, plan, safeShots, generatedAssets);
        script.setCategoryCode(firstText(request.categoryCode(), script.getCategoryCode(), "advertisement"));
        script.setDurationSeconds(intValue(request.durationSeconds(), script.getDurationSeconds() == null ? 60 : script.getDurationSeconds()));
        script.setScreenType(firstText(request.screenType(), script.getScreenType(), "vertical"));
        script.setTotalShots(safeShots.size());
        script.setSceneCount(safeShots.size());
        script.setSequenceCount(1);
        script.setTitle(firstText(payload.get("projectTitle"), script.getTitle(), "Product ad"));
        script.setScriptText(productScriptText(plan, safeShots));
        script.setScriptPayload(payload);
        script.setShots(safeShots);
        script.setUpdatedAt(OffsetDateTime.now());
    }

    private Map<String, Object> productScriptPayload(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            Map<String, Object> plan,
            List<Map<String, Object>> shots,
            List<Map<String, Object>> generatedAssets
    ) {
        Map<String, Object> product = mapValue(plan.get("productIntelligence"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectTitle", productTitle(request, product));
        payload.put("title", productTitle(request, product));
        payload.put("script", firstText(plan.get("storyScript"), plan.get("script")));
        payload.put("shots", shots);
        payload.put("scenes", shots);
        payload.put("totalShots", shots.size());
        payload.put("sceneCount", shots.size());
        payload.put("duration", intValue(request.durationSeconds(), 60));
        payload.put("durationSeconds", intValue(request.durationSeconds(), 60));
        payload.put("screenType", firstText(request.screenType(), "vertical"));
        payload.put("categoryCode", firstText(request.categoryCode(), "advertisement"));
        payload.put("platformCode", firstText(request.platformCode(), "instagram_reels"));
        payload.put("productionStyle", "FULL_AI");
        payload.put("hybridSceneMode", "AI_ONLY");
        payload.put("screenplayApprovedForVideo", true);
        payload.put("approvalStatus", "AUTO_APPROVED_PRODUCT_AD_PIPELINE");
        payload.put("approvedForVideoAt", OffsetDateTime.now().toString());
        payload.put("productIntelligence", product);
        payload.put("productIntelligenceBrief", request.existingBrief() == null ? Map.of() : request.existingBrief());
        payload.put("productImageUrls", referenceImageUrls(request, plan));
        payload.put("referenceImageUrls", referenceImageUrls(request, plan));
        payload.put("productImageAssets", productReferenceImageAssets(request));
        payload.put("referenceImageAssets", productReferenceImageAssets(request));
        payload.put("ingredientDetails", request.ingredientDetails());
        payload.put("adTone", firstText(product.get("adTone"), product.get("tone"), request.tone()));
        payload.put("toneRationale", firstText(product.get("toneRationale"), plan.get("toneRationale")));
        payload.put("creativeBrief", plan.get("creativeBrief"));
        payload.put("adFormat", plan.get("adFormat"));
        payload.put("adFormatKey", firstMap(plan.get("creativeBrief")).get("adFormatKey"));
        payload.put("formatPlaybook", firstMap(plan.get("creativeBrief")).get("formatPlaybook"));
        payload.put("selectedShotTypes", plan.get("selectedShotTypes"));
        payload.put("noHumans", plan.get("noHumans"));
        payload.put("shotPlanningMode", plan.get("shotPlanningMode"));
        payload.put("marketResearch", plan.get("marketResearch"));
        payload.put("adConcepts", plan.get("adConcepts"));
        payload.put("campaignStrategy", plan.get("campaignStrategy"));
        payload.put("hookPlan", plan.get("hookPlan"));
        payload.put("retentionPlan", plan.get("retentionPlan"));
        payload.put("dialoguePlan", plan.get("dialoguePlan"));
        payload.put("musicPlan", plan.get("musicPlan"));
        payload.put("freeMusicPlan", plan.get("freeMusicPlan"));
        payload.put("soundDesignPlan", plan.get("soundDesignPlan"));
        payload.put("audioProductionPlan", Map.of(
                "dialoguePlan", firstMap(plan.get("dialoguePlan")),
                "musicPlan", firstMap(plan.get("musicPlan")),
                "freeMusicPlan", firstMap(plan.get("freeMusicPlan")),
                "soundDesignPlan", firstMap(plan.get("soundDesignPlan")),
                "audioMixStandards", audioMixStandards()
        ));
        payload.put("videoFinishingPlan", plan.get("videoFinishingPlan"));
        payload.put("editingPlan", plan.get("editingPlan"));
        payload.put("editorHandoffPlan", firstMap(plan.get("editorHandoffPlan"), plan.get("editingPlan")));
        payload.put("recommendedEditingTools", firstValue(
                firstMap(plan.get("editorHandoffPlan")).get("recommendedTools"),
                firstMap(plan.get("editingPlan")).get("recommendedTools")
        ));
        payload.put("imagePrompts", plan.get("imagePrompts"));
        payload.put("generatedAssets", generatedAssets);
        payload.put("videoPacingProfile", plan.get("videoPacingProfile"));
        payload.put("videoConsistencyBible", plan.get("videoConsistencyBible"));
        payload.put("srt", plan.get("srt"));
        payload.put("srtFile", plan.get("srtFile"));
        payload.put("srtCues", plan.get("srtCues"));
        payload.put("productAdPipeline", Map.of(
                "generationJobId", jobId == null ? "" : jobId.toString(),
                "imageProvider", firstText(request.imageProvider(), "google"),
                "imageModel", firstText(request.imageModel(), "imagen"),
                "generatedAt", OffsetDateTime.now().toString()
        ));
        return payload;
    }

    private Map<String, Object> productWorkflowContext(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            Map<String, Object> plan
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("productAdPipelineJobId", jobId.toString());
        context.put("productUrl", request.productUrl());
        context.put("productName", request.productName());
        context.put("productImageUrls", request.productImageUrls() == null ? List.of() : request.productImageUrls());
        context.put("referenceImageUrls", referenceImageUrls(request, plan));
        context.put("productImageAssets", productReferenceImageAssets(request));
        context.put("referenceImageAssets", productReferenceImageAssets(request));
        context.put("productIntelligenceBrief", request.existingBrief() == null ? Map.of() : request.existingBrief());
        context.put("campaignObjective", request.campaignObjective());
        context.put("targetAudience", request.targetAudience());
        context.put("ingredientDetails", request.ingredientDetails());
        context.put("tone", request.tone());
        context.put("adTone", firstText(
                mapValue(plan.get("productIntelligence")).get("adTone"),
                mapValue(plan.get("productIntelligence")).get("tone"),
                request.tone()
        ));
        context.put("toneRationale", firstText(
                mapValue(plan.get("productIntelligence")).get("toneRationale"),
                plan.get("toneRationale")
        ));
        context.put("categoryCode", firstText(request.categoryCode(), "advertisement"));
        context.put("platformCode", firstText(request.platformCode(), "instagram_reels"));
        context.put("durationSeconds", intValue(request.durationSeconds(), 60));
        context.put("creativeBrief", plan.get("creativeBrief"));
        context.put("adFormat", plan.get("adFormat"));
        context.put("adFormatKey", firstMap(plan.get("creativeBrief")).get("adFormatKey"));
        context.put("formatPlaybook", firstMap(plan.get("creativeBrief")).get("formatPlaybook"));
        context.put("selectedShotTypes", plan.get("selectedShotTypes"));
        context.put("noHumans", plan.get("noHumans"));
        context.put("shotPlanningMode", plan.get("shotPlanningMode"));
        context.put("productionStyle", "FULL_AI");
        context.put("hybridSceneMode", "AI_ONLY");
        context.put("productIntelligence", plan.get("productIntelligence"));
        context.put("marketResearch", plan.get("marketResearch"));
        context.put("adConcepts", plan.get("adConcepts"));
        context.put("hookPlan", plan.get("hookPlan"));
        context.put("retentionPlan", plan.get("retentionPlan"));
        context.put("videoConsistencyBible", plan.get("videoConsistencyBible"));
        context.put("videoPacingProfile", plan.get("videoPacingProfile"));
        return context;
    }

    private GenerateProductAdPipelineRequest withWorkflowIds(GenerateProductAdPipelineRequest request, ProductAdWorkflow workflow) {
        return new GenerateProductAdPipelineRequest(
                request.productUrl(),
                request.productName(),
                request.productImageUrls(),
                request.campaignObjective(),
                request.targetAudience(),
                request.ingredientDetails(),
                request.tone(),
                request.categoryCode(),
                request.platformCode(),
                request.durationSeconds(),
                request.conceptCount(),
                request.imageCount(),
                request.screenType(),
                request.pacingStyle(),
                request.imageProvider(),
                request.imageModel(),
                request.noHumans(),
                request.autoPlanShotTypes(),
                request.generateImages(),
                request.useWebSearch(),
                request.autoPrepareVideoRun(),
                workflow.projectId(),
                workflow.lockedIdeaId(),
                workflow.storyIdeaId(),
                workflow.scriptId(),
                request.existingBrief(),
                request.brandContext(),
                request.idempotencyKey()
        );
    }

    private String productTitle(GenerateProductAdPipelineRequest request, Map<String, Object> product) {
        return firstText(
                product.get("name"),
                product.get("productName"),
                request.productName(),
                request.productUrl(),
                "Product ad"
        );
    }

    private String productSummary(GenerateProductAdPipelineRequest request, Map<String, Object> product, Map<String, Object> plan) {
        return firstText(
                product.get("summary"),
                product.get("usp"),
                plan.get("strategySummary"),
                request.campaignObjective(),
                "Product ad generated from supplied product details and image links."
        );
    }

    private String productScriptText(Map<String, Object> plan, List<Map<String, Object>> shots) {
        String script = firstText(plan.get("storyScript"), plan.get("script"), plan.get("voiceoverScript"));
        if (!script.isBlank()) {
            return script;
        }
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> shot : shots == null ? List.<Map<String, Object>>of() : shots) {
            String line = firstText(
                    shot.get("voiceover"),
                    shot.get("dialogue"),
                    shot.get("caption"),
                    shot.get("textOverlay"),
                    shot.get("action"),
                    shot.get("description")
            );
            if (!line.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private CreatorAsset saveGeneratedImageAsset(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            String assetKind,
            String prompt,
            StoryboardImageGenerationService.GeneratedImage generatedImage,
            int index
    ) {
        String contentType = firstText(generatedImage.contentType(), "image/png");
        String extension = extensionFor(contentType);
        String objectKey = "product-ads/%s/%s/%02d-%s.%s".formatted(
                slug(tenantId),
                jobId,
                index,
                UUID.randomUUID(),
                extension
        );
        AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAsset(
                objectKey,
                generatedImage.bytes(),
                contentType,
                SIGNED_URL_TTL
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productAdPipelineJobId", jobId.toString());
        metadata.put("lockedIdeaId", request.lockedIdeaId() == null ? null : request.lockedIdeaId().toString());
        metadata.put("storyIdeaId", request.storyIdeaId() == null ? null : request.storyIdeaId().toString());
        metadata.put("scriptId", request.scriptId() == null ? null : request.scriptId().toString());
        metadata.put("assetKind", assetKind);
        metadata.put("prompt", prompt);
        metadata.put("imageGeneration", generatedImage.metadata() == null ? Map.of() : generatedImage.metadata());
        metadata.put("signedUrlTtlSeconds", SIGNED_URL_TTL.toSeconds());
        metadata.put("signedUrlGeneratedAt", OffsetDateTime.now().toString());
        return assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(tenantId)
                .userId(userId)
                .projectId(request.projectId())
                .storyboardId(null)
                .assetType(ASSET_TYPE_PRODUCT_AD_IMAGE)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());
    }

    private UUID insertProductAdAssetRow(
            UUID jobId,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            String assetKind,
            String prompt
    ) {
        UUID rowId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into creator_product_ad_assets (
                    id,
                    tenant_id,
                    user_id,
                    project_id,
                    locked_idea_id,
                    story_idea_id,
                    script_id,
                    generation_job_id,
                    asset_kind,
                    status,
                    prompt,
                    metadata,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, cast(? as jsonb), now(), now())
                """,
                rowId,
                tenantId,
                userId,
                request.projectId(),
                request.lockedIdeaId(),
                request.storyIdeaId(),
                request.scriptId(),
                jobId,
                assetKind,
                prompt,
                toJson(Map.of("stage", "image_generation_started"))
        );
        return rowId;
    }

    private void updateProductAdAssetSuccess(UUID rowId, CreatorAsset mediaAsset, String signedUrl, Map<String, Object> metadata) {
        Map<String, Object> generation = mapValue(metadata == null ? null : metadata.get("imageGeneration"));
        jdbcTemplate.update(
                """
                update creator_product_ad_assets
                   set status = 'COMPLETED',
                       media_asset_id = ?,
                       provider = ?,
                       model = ?,
                       asset_url = ?,
                       metadata = cast(? as jsonb),
                       error_message = null,
                       updated_at = now()
                 where id = ?
                """,
                mediaAsset.getId(),
                firstText(generation.get("provider"), metadata == null ? null : metadata.get("provider")),
                firstText(generation.get("model"), metadata == null ? null : metadata.get("model")),
                signedUrl,
                toJson(metadata == null ? Map.of() : metadata),
                rowId
        );
    }

    private void updateProductAdAssetFailure(UUID rowId, RuntimeException ex) {
        jdbcTemplate.update(
                """
                update creator_product_ad_assets
                   set status = 'FAILED',
                       error_message = ?,
                       metadata = metadata || cast(? as jsonb),
                       updated_at = now()
                 where id = ?
                """,
                defaultString(ex.getMessage(), ex.getClass().getSimpleName()),
                toJson(Map.of("errorType", ex.getClass().getSimpleName())),
                rowId
        );
    }

    private void publishImageDebit(
            StoryboardImageGenerationService.GeneratedImage generatedImage,
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            UUID jobId,
            String assetKind
    ) {
        Map<String, Object> metadata = generatedImage == null ? Map.of() : mapValue(generatedImage.metadata());
        Map<String, Object> costMetadata = mapValue(metadata.get("costMetadata"));
        if (costMetadata.isEmpty()) {
            return;
        }
        creatorAiService.publishProviderUsageDebit(
                "PRODUCT_AD_IMAGE_GENERATE",
                firstText(costMetadata.get("provider"), metadata.get("provider"), "gemini"),
                firstText(costMetadata.get("model"), metadata.get("model"), request.imageModel()),
                costMetadata,
                new CreatorAiService.AiUsageContext(tenantId, userId, request.projectId(), jobId, null),
                "Product ad image anchor generation: " + assetKind
        );
    }

    private List<StoryboardImageGenerationService.ReferenceImageInput> downloadReferenceImages(List<String> urls) {
        List<StoryboardImageGenerationService.ReferenceImageInput> references = new ArrayList<>();
        for (String url : urls == null ? List.<String>of() : urls.stream().limit(8).toList()) {
            try {
                URI uri = URI.create(url);
                ResponseEntity<byte[]> response = webClient
                        .get()
                        .uri(uri)
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
                        "product_reference_" + (references.size() + 1)
                ));
            } catch (RuntimeException ex) {
                log.debug("Product reference image download skipped url={} errorType={} errorMessage={}", url, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        return references;
    }

    private List<Map<String, Object>> productReferenceImageAssets(GenerateProductAdPipelineRequest request) {
        Map<String, Object> existingBrief = request == null || request.existingBrief() == null
                ? Map.of()
                : request.existingBrief();
        List<Map<String, Object>> assets = new ArrayList<>();
        Object[] assetSources = {
                existingBrief.get("imageAssets"),
                existingBrief.get("productImageAssets"),
                existingBrief.get("referenceImageAssets")
        };
        for (Object value : assetSources) {
            for (Map<String, Object> asset : mapListValue(value)) {
                String bucket = firstText(asset.get("bucket"));
                String objectKey = firstText(asset.get("objectKey"), asset.get("object_key"));
                if (bucket.isBlank() || objectKey.isBlank()) {
                    continue;
                }
                boolean duplicate = assets.stream().anyMatch(existing ->
                        bucket.equals(firstText(existing.get("bucket")))
                                && objectKey.equals(firstText(existing.get("objectKey"), existing.get("object_key"))));
                if (!duplicate) {
                    Map<String, Object> canonical = new LinkedHashMap<>(asset);
                    canonical.put("referenceRole", "canonical_product_reference");
                    canonical.put("assetRole", "canonical_product_reference");
                    assets.add(canonical);
                }
                if (assets.size() >= 8) {
                    return assets;
                }
            }
        }
        return assets;
    }

    private List<String> referenceImageUrls(GenerateProductAdPipelineRequest request, Map<String, Object> plan) {
        List<String> urls = new ArrayList<>();
        urls.addAll(request.productImageUrls() == null ? List.of() : request.productImageUrls());
        Map<String, Object> product = mapValue(plan.get("productIntelligence"));
        urls.addAll(stringList(product.get("sourceImages")));
        urls.addAll(stringList(product.get("imageUrls")));
        return urls.stream()
                .map(value -> defaultString(value, "").trim())
                .filter(value -> value.startsWith("http://") || value.startsWith("https://"))
                .distinct()
                .limit(8)
                .toList();
    }

    private String buildImagePrompt(
            Map<String, Object> imagePrompt,
            Map<String, Object> plan,
            GenerateProductAdPipelineRequest request,
            int index
    ) {
        Map<String, Object> product = mapValue(plan.get("productIntelligence"));
        Map<String, Object> consistency = mapValue(plan.get("videoConsistencyBible"));
        return """
                Generate one production-ready product ad image anchor, not a storyboard sketch.

                Product intelligence:
                %s

                Consistency bible:
                %s

                Image brief:
                %s

                Requirements:
                - 9:16 vertical unless the request explicitly says horizontal.
                - Keep product packaging, logo, label geometry, colors, and physical proportions consistent across the campaign.
                - If product reference images are attached, use them as the packaging identity source.
                - No made-up certifications, medical claims, discounts, or text that was not supplied.
                - Leave mobile-safe space for captions.
                - Make the image useful as an image-to-video anchor. Include clear foreground, background, lighting, and motion affordance.
                - Output exactly one image.

                Campaign screenType: %s
                Asset number: %d
                """.formatted(
                toJson(product),
                toJson(consistency),
                toJson(imagePrompt),
                firstText(request.screenType(), "vertical"),
                index
        ).trim();
    }

    private List<Map<String, Object>> attachGeneratedAssetsToShots(
            Map<String, Object> plan,
            List<Map<String, Object>> generatedAssets
    ) {
        List<Map<String, Object>> shots = mapListValue(plan.get("shotPlan"));
        if (shots.isEmpty()) {
            return List.of();
        }
        Map<Integer, Map<String, Object>> assetByShot = new LinkedHashMap<>();
        for (Map<String, Object> asset : generatedAssets == null ? List.<Map<String, Object>>of() : generatedAssets) {
            if (!"COMPLETED".equalsIgnoreCase(firstText(asset.get("status")))) {
                continue;
            }
            int shotNumber = intValue(asset.get("shotNumber"), assetByShot.size() + 1);
            assetByShot.putIfAbsent(shotNumber, asset);
        }
        List<Map<String, Object>> anchored = new ArrayList<>();
        for (int index = 0; index < shots.size(); index++) {
            Map<String, Object> shot = new LinkedHashMap<>(shots.get(index));
            int shotNumber = intValue(firstValue(shot.get("shotNumber"), shot.get("sceneNumber")), index + 1);
            Map<String, Object> asset = assetByShot.getOrDefault(shotNumber, assetByShot.get(index + 1));
            if (asset != null && !asset.isEmpty()) {
                String assetUrl = firstText(asset.get("assetUrl"), asset.get("signedUrl"));
                shot.put("referenceImageUrl", assetUrl);
                shot.put("generatedImageUrl", assetUrl);
                shot.put("generatedProductImageUrl", assetUrl);
                shot.put("productImageUrl", assetUrl);
                shot.put("productImageAssets", List.of(asset));
                shot.put("generatedProductImageAssets", List.of(asset));
                shot.put("imageAssets", List.of(asset));
                shot.put("videoMotionPrompt", firstText(asset.get("videoMotionPrompt"), shot.get("videoMotionPrompt"), shot.get("motion")));
                shot.put("imageAnchorAssetId", asset.get("id"));
                shot.put("imageAnchorMediaAssetId", asset.get("mediaAssetId"));
            }
            shot.put("generationMode", firstText(shot.get("generationMode"), "AI_GENERATED"));
            anchored.add(shot);
        }
        return anchored;
    }

    private Map<String, Object> buildVideoRequest(
            GenerateProductAdPipelineRequest request,
            Map<String, Object> plan,
            List<Map<String, Object>> anchoredShots,
            List<Map<String, Object>> generatedAssets
    ) {
        Map<String, Object> requestMap = new LinkedHashMap<>();
        Map<String, Object> existing = mapValue(request.existingBrief());
        Map<String, Object> brandContext = mapValue(request.brandContext());
        requestMap.put("projectId", request.projectId() == null ? null : request.projectId().toString());
        requestMap.put("lockedIdeaId", request.lockedIdeaId() == null ? null : request.lockedIdeaId().toString());
        requestMap.put("storyIdeaId", request.storyIdeaId() == null ? null : request.storyIdeaId().toString());
        requestMap.put("scriptId", request.scriptId() == null ? null : request.scriptId().toString());
        requestMap.put("provider", firstText(existing.get("videoProvider"), brandContext.get("videoProvider"), "seedance"));
        requestMap.put("model", firstText(existing.get("videoModel"), brandContext.get("videoModel")));
        requestMap.put("targetDurationSeconds", intValue(request.durationSeconds(), 60));
        requestMap.put("durationSeconds", intValue(request.durationSeconds(), 60));
        requestMap.put("screenType", firstText(request.screenType(), "vertical"));
        requestMap.put("productionStyle", "FULL_AI");
        requestMap.put("hybridSceneMode", "AI_ONLY");
        requestMap.put("referenceImageMode", "product_motion_anchor");
        requestMap.put("storyboardReferenceMode", "use_product_image_anchors");
        requestMap.put("requireImageAnchors", !generatedAssets.isEmpty());
        requestMap.put("imageLedAdMode", true);
        requestMap.put("imageLedAdPlan", Map.of(
                "enabled", true,
                "referenceImageMode", "product_motion_anchor",
                "providerPolicy", "generate_or_use_approved_product_image_anchors_then_render_video_from_image",
                "anchorRole", "product_visual_anchor",
                "generatedAssets", generatedAssets
        ));
        requestMap.put("productImageAssets", generatedAssets);
        requestMap.put("productIntelligence", plan.get("productIntelligence"));
        requestMap.put("creativeBrief", plan.get("creativeBrief"));
        requestMap.put("adFormat", plan.get("adFormat"));
        requestMap.put("adFormatKey", firstMap(plan.get("creativeBrief")).get("adFormatKey"));
        requestMap.put("formatPlaybook", firstMap(plan.get("creativeBrief")).get("formatPlaybook"));
        requestMap.put("selectedShotTypes", plan.get("selectedShotTypes"));
        requestMap.put("noHumans", plan.get("noHumans"));
        requestMap.put("shotPlanningMode", plan.get("shotPlanningMode"));
        requestMap.put("marketResearch", plan.get("marketResearch"));
        requestMap.put("adConcepts", plan.get("adConcepts"));
        requestMap.put("hookPlan", plan.get("hookPlan"));
        requestMap.put("retentionPlan", plan.get("retentionPlan"));
        requestMap.put("videoFinishingPlan", plan.get("videoFinishingPlan"));
        requestMap.put("soundDesignPlan", plan.get("soundDesignPlan"));
        Map<String, Object> audioProductionPlan = new LinkedHashMap<>();
        audioProductionPlan.put("dialoguePlan", firstMap(plan.get("dialoguePlan")));
        audioProductionPlan.put("musicPlan", firstMap(plan.get("musicPlan")));
        audioProductionPlan.put("freeMusicPlan", firstMap(plan.get("freeMusicPlan")));
        audioProductionPlan.put("soundDesignPlan", firstMap(plan.get("soundDesignPlan")));
        audioProductionPlan.put("audioMixStandards", audioMixStandards());
        requestMap.put("audioProductionPlan", audioProductionPlan);
        Map<String, Object> editorHandoffPlan = firstMap(
                plan.get("editorHandoffPlan"),
                plan.get("editingPlan"),
                mapValue(plan.get("videoFinishingPlan")).get("editorHandoffPlan"),
                mapValue(plan.get("videoFinishingPlan")).get("editingPlan")
        );
        requestMap.put("editingPlan", editorHandoffPlan);
        requestMap.put("editorHandoffPlan", editorHandoffPlan);
        requestMap.put("recommendedEditingTools", firstValue(
                editorHandoffPlan.get("recommendedTools"),
                editorHandoffPlan.get("toolsToUse")
        ));
        requestMap.put("videoPacingProfile", plan.get("videoPacingProfile"));
        requestMap.put("videoConsistencyBible", plan.get("videoConsistencyBible"));
        requestMap.put("seedancePromptStrategy", Map.of(
                "strategy", "product_image_anchor_plus_deterministic_seed",
                "fastPacedPrompt", "Fast-paced product commercial: land the product-relevant hook in the first two seconds, introduce a new proof or pattern interrupt every 2-4 seconds, then end on a clean packshot and CTA.",
                "slowPacedPrompt", "Cinematic product commercial with a specific opening hook, slower premium camera moves, controlled proof beats, and a readable final packshot.",
                "hookPlan", firstMap(plan.get("hookPlan")),
                "retentionPlan", firstMap(plan.get("retentionPlan"))
        ));
        requestMap.put("seriesSeed", deterministicSeed(firstText(request.productUrl(), request.productName(), UUID.randomUUID().toString())));
        requestMap.put("srt", plan.get("srt"));
        requestMap.put("srtFile", plan.get("srtFile"));
        requestMap.put("srtCues", plan.get("srtCues"));
        requestMap.put("scenes", anchoredShots);
        return requestMap;
    }

    private Map<String, Object> audioMixStandards() {
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("dialogueLevel", "consistent_speech_first");
        standards.put("backgroundMusicDucking", "duck_under_speech");
        standards.put("ambientRoomTone", "scene_matched_low_bed");
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

    private int imageProgress(int index, int total) {
        if (total <= 0) {
            return 45;
        }
        return Math.min(88, 42 + (int) Math.floor((index / (double) total) * 44));
    }

    private Map<String, Object> requestToMap(GenerateProductAdPipelineRequest request) {
        return objectMapper.convertValue(request, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private String productAdIdempotencyKey(GenerateProductAdPipelineRequest request) {
        Map<String, Object> stable = requestToMap(request);
        stable.remove("idempotencyKey");
        return "product-ad:" + sha256Hex(toJson(stable));
    }

    private int deterministicSeed(String value) {
        String hash = sha256Hex(defaultString(value, "product-ad"));
        return Math.abs(hash.substring(0, 8).hashCode());
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(defaultString(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private String extensionFor(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("jpeg") || normalized.contains("jpg")) {
            return "jpg";
        }
        if (normalized.contains("webp")) {
            return "webp";
        }
        return "png";
    }

    private String slug(String value) {
        String normalized = defaultString(value, "asset").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            return "asset";
        }
        return normalized.length() > 56 ? normalized.substring(0, 56) : normalized;
    }

    private List<Map<String, Object>> mapListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) {
                maps.add(map);
            }
        }
        return maps;
    }

    private Map<String, Object> firstMap(Object... values) {
        for (Object value : values) {
            Map<String, Object> map = mapValue(value);
            if (!map.isEmpty()) {
                return map;
            }
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
        }
        String text = stringValue(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    private Object firstValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        Object value = firstValue(values);
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value, int fallback) {
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

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private record ProductAdWorkflow(UUID projectId, UUID lockedIdeaId, UUID storyIdeaId, UUID scriptId) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("projectId", projectId == null ? null : projectId.toString());
            map.put("lockedIdeaId", lockedIdeaId == null ? null : lockedIdeaId.toString());
            map.put("storyIdeaId", storyIdeaId == null ? null : storyIdeaId.toString());
            map.put("scriptId", scriptId == null ? null : scriptId.toString());
            return map;
        }
    }
}
