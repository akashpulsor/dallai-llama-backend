package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorPromptTemplate;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.response.ShotProductionPlanTagResponse;
import com.dalai.llama.creator.exception.CreatorAiOutputException;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductionPlanTagService {

    private static final Logger log = LoggerFactory.getLogger(ProductionPlanTagService.class);
    private static final int AI_TRANSIENT_MAX_ATTEMPTS = 3;
    public static final String DEFAULT_STYLE_KEY = "indian_creator_pencil";

    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final CreatorScriptRepository scriptRepository;
    private final CreatorPromptRunRepository promptRunRepository;
    private final PromptTemplateService promptTemplateService;
    private final CreatorAiService creatorAiService;
    private final GenerationJobService generationJobService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public ProductionPlanTagService(
            CreatorScriptShotPlanRepository shotPlanRepository,
            CreatorScriptRepository scriptRepository,
            CreatorPromptRunRepository promptRunRepository,
            PromptTemplateService promptTemplateService,
            CreatorAiService creatorAiService,
            GenerationJobService generationJobService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.shotPlanRepository = shotPlanRepository;
        this.scriptRepository = scriptRepository;
        this.promptRunRepository = promptRunRepository;
        this.promptTemplateService = promptTemplateService;
        this.creatorAiService = creatorAiService;
        this.generationJobService = generationJobService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public List<ShotProductionPlanTagResponse> generateTagsForScript(
            CreatorScript script,
            Map<String, Object> scriptPayload,
            List<Map<String, Object>> shotPayloads,
            String styleKey
    ) {
        String normalizedStyleKey = normalizeStyleKey(styleKey);
        List<Map<String, Object>> shots = safeShots(script, shotPayloads);
        if (script == null || script.getId() == null || shots.isEmpty()) {
            return List.of();
        }

        CreatorGenerationJob job = startProductionPlanJob(script, shots, shots, normalizedStyleKey, null);
        return generateTagsForScriptWithJob(script, scriptPayload, shots, normalizedStyleKey, job.getId(), null);
    }

    @Transactional
    public CreatorGenerationJob startGenerateTagsForScriptJob(
            UUID scriptId,
            String styleKey,
            Integer focusedShotNumber,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<Map<String, Object>> shots = safeShots(script, null);
        if (shots.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenplay has no shots for shot plan generation.");
        }
        String normalizedStyleKey = normalizeStyleKey(styleKey);
        List<Map<String, Object>> targetShots = focusedShots(shots, focusedShotNumber);
        return startProductionPlanJob(script, shots, targetShots, normalizedStyleKey, focusedShotNumber);
    }

    @Transactional
    public List<ShotProductionPlanTagResponse> runGenerateTagsForScriptJob(
            UUID generationJobId,
            UUID scriptId,
            String styleKey,
            Integer focusedShotNumber,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        List<Map<String, Object>> shots = safeShots(script, null);
        if (shots.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenplay has no shots for shot plan generation.");
        }
        return generateTagsForScriptWithJob(script, script.getScriptPayload(), shots, normalizeStyleKey(styleKey), generationJobId, focusedShotNumber);
    }

    private List<ShotProductionPlanTagResponse> generateTagsForScriptWithJob(
            CreatorScript script,
            Map<String, Object> scriptPayload,
            List<Map<String, Object>> shots,
            String normalizedStyleKey,
            UUID generationJobId,
            Integer focusedShotNumber
    ) {
        Map<String, Object> projectContext = buildProjectContext(script, scriptPayload, normalizedStyleKey);
        List<Map<String, Object>> targetShots = focusedShots(shots, focusedShotNumber);
        try {
            List<ShotProductionPlanTagResponse> responses = new ArrayList<>();
            for (int index = 0; index < targetShots.size(); index++) {
                Map<String, Object> shot = targetShots.get(index);
                int shotNumber = intValue(shot.get("shotNumber"), index + 1);
                Map<String, Object> inputPayload = buildInputPayload(script, projectContext, shot, normalizedStyleKey);

                GeneratedTag storyboardTag = generateTag(
                        PromptTemplateType.STORYBOARD_TAG_GENERATE.name(),
                        "storyboardTag",
                        script,
                        generationJobId,
                        inputPayload,
                        storyboardTagSchemaReference(script, projectContext, shot),
                        shotNumber
                );
                GeneratedTag lightingTag = generateTag(
                        PromptTemplateType.LIGHTING_BUILD_SHEET_TAG_GENERATE.name(),
                        "lightingBuildSheetTag",
                        script,
                        generationJobId,
                        inputPayload,
                        lightingBuildSheetTagSchemaReference(script, projectContext, shot),
                        shotNumber
                );
                GeneratedTag cameraTag = generateTag(
                        PromptTemplateType.CAMERA_PLAN_SHEET_TAG_GENERATE.name(),
                        "cameraPlanSheetTag",
                        script,
                        generationJobId,
                        inputPayload,
                        cameraPlanSheetTagSchemaReference(script, projectContext, shot),
                        shotNumber
                );

                Map<String, Object> promptRunIds = new LinkedHashMap<>();
                putIfPresent(promptRunIds, "storyboardTagPromptRunId", storyboardTag.promptRunId());
                putIfPresent(promptRunIds, "lightingBuildSheetPromptRunId", lightingTag.promptRunId());
                putIfPresent(promptRunIds, "cameraPlanSheetPromptRunId", cameraTag.promptRunId());

                CreatorScriptShotPlan savedPlan = saveShotPlan(
                        script,
                        generationJobId,
                        shotNumber,
                        normalizedStyleKey,
                        storyboardTag.payload(),
                        lightingTag.payload(),
                        cameraTag.payload(),
                        promptRunIds,
                        inputPayload
                );
                responses.add(toResponse(savedPlan));

                int progress = 10 + (int) Math.round(((index + 1) * 80.0d) / Math.max(1, targetShots.size()));
                generationJobService.updateGenerationJobProgress(generationJobId, progress, "Generated production plan JSON for shot " + shotNumber);
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("scriptId", script.getId().toString());
            output.put("shotCount", targetShots.size());
            output.put("totalScreenplayShots", shots.size());
            if (focusedShotNumber != null) {
                output.put("focusedShotNumber", focusedShotNumber);
            }
            output.put("styleKey", normalizedStyleKey);
            output.put("planIds", responses.stream().map(item -> item.planId().toString()).toList());
            output.put("productionPlanTags", objectMapper.convertValue(
                    responses,
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            ));
            generationJobService.completeGenerationJob(generationJobId, output);
            return responses;
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(
                    generationJobId,
                    defaultString(ex.getMessage(), ex.getClass().getSimpleName()),
                    ex instanceof CreatorAiOutputException aiOutputException
                            ? new LinkedHashMap<>(aiOutputException.getDebugPayload())
                            : Map.of()
            );
            throw ex;
        }
    }

    private CreatorScriptShotPlan saveShotPlan(
            CreatorScript script,
            UUID generationJobId,
            int shotNumber,
            String normalizedStyleKey,
            Map<String, Object> storyboardTag,
            Map<String, Object> lightingTag,
            Map<String, Object> cameraTag,
            Map<String, Object> promptRunIds,
            Map<String, Object> inputPayload
    ) {
        UUID planId = jdbcTemplate.queryForObject(
                """
                insert into creator_script_shot_plans (
                    id,
                    tenant_id,
                    user_id,
                    script_id,
                    locked_idea_id,
                    story_idea_id,
                    generation_job_id,
                    shot_number,
                    style_key,
                    storyboard_tag,
                    lighting_build_sheet_tag,
                    camera_plan_sheet_tag,
                    prompt_run_ids,
                    input_payload,
                    status,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 'GENERATED', now(), now())
                on conflict (script_id, shot_number, style_key) do update set
                    tenant_id = excluded.tenant_id,
                    user_id = excluded.user_id,
                    locked_idea_id = excluded.locked_idea_id,
                    story_idea_id = excluded.story_idea_id,
                    generation_job_id = excluded.generation_job_id,
                    storyboard_tag = excluded.storyboard_tag,
                    lighting_build_sheet_tag = excluded.lighting_build_sheet_tag,
                    camera_plan_sheet_tag = excluded.camera_plan_sheet_tag,
                    prompt_run_ids = excluded.prompt_run_ids,
                    input_payload = excluded.input_payload,
                    status = excluded.status,
                    updated_at = now()
                returning id
                """,
                UUID.class,
                UUID.randomUUID(),
                script.getTenantId(),
                script.getUserId(),
                script.getId(),
                script.getLockedIdeaId(),
                script.getStoryIdeaId(),
                generationJobId,
                shotNumber,
                normalizedStyleKey,
                toJson(storyboardTag == null ? new LinkedHashMap<>() : storyboardTag),
                toJson(lightingTag == null ? new LinkedHashMap<>() : lightingTag),
                toJson(cameraTag == null ? new LinkedHashMap<>() : cameraTag),
                toJson(promptRunIds == null ? new LinkedHashMap<>() : promptRunIds),
                toJson(inputPayload == null ? new LinkedHashMap<>() : inputPayload)
        );
        return shotPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("Saved shot plan was not found: " + planId));
    }

    private List<Map<String, Object>> focusedShots(List<Map<String, Object>> shots, Integer focusedShotNumber) {
        if (focusedShotNumber == null) {
            return shots == null ? List.of() : shots;
        }
        List<Map<String, Object>> source = shots == null ? List.of() : shots;
        List<Map<String, Object>> target = source.stream()
                .filter(shot -> intValue(shot.get("shotNumber"), 0) == focusedShotNumber)
                .toList();
        if (target.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shot " + focusedShotNumber + " was not found in the screenplay.");
        }
        return target;
    }

    private CreatorGenerationJob startProductionPlanJob(CreatorScript script, List<Map<String, Object>> shots, List<Map<String, Object>> targetShots, String normalizedStyleKey, Integer focusedShotNumber) {
        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("scriptId", script.getId().toString());
        jobInput.put("screenplayId", script.getId().toString());
        jobInput.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        jobInput.put("lockedIdeaId", stringValue(script.getLockedIdeaId()));
        jobInput.put("storyIdeaId", stringValue(script.getStoryIdeaId()));
        jobInput.put("shotCount", targetShots.size());
        jobInput.put("totalScreenplayShots", shots.size());
        if (focusedShotNumber != null) {
            jobInput.put("focusedShotNumber", focusedShotNumber);
        }
        jobInput.put("styleKey", normalizedStyleKey);

        return generationJobService.startGenerationJob(
                PromptTemplateType.SHOT_PRODUCTION_PLAN_GENERATE.name(),
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                jobInput
        );
    }

    @Transactional(readOnly = true)
    public List<ShotProductionPlanTagResponse> listTagsForScript(UUID scriptId) {
        if (scriptId == null) {
            return List.of();
        }
        return shotPlanRepository.findByScriptIdOrderByShotNumberAsc(scriptId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShotProductionPlanTagResponse> listTagsForScript(UUID scriptId, String tenantId, String userId) {
        if (scriptId == null) {
            return List.of();
        }
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        return listTagsForScript(script.getId());
    }

    private CreatorScript loadScript(UUID scriptId, String tenantId, String userId) {
        return scriptRepository.findByIdAndTenantIdAndUserId(
                        scriptId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Generated screenplay was not found."));
    }

    private GeneratedTag generateTag(
            String promptType,
            String rootKey,
            CreatorScript script,
            UUID generationJobId,
            Map<String, Object> inputPayload,
            Map<String, Object> schemaReference,
            int shotNumber
    ) {
        Map<String, Object> providerOutputForDebug = new LinkedHashMap<>();
        try {
            CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(promptType);
            Map<String, Object> renderVariables = new LinkedHashMap<>(inputPayload);
            renderVariables.put("shotJson", toJson(inputPayload.get("shot")));
            renderVariables.put("projectContextJson", toJson(inputPayload.get("projectContext")));
            renderVariables.put("styleKey", inputPayload.get("styleKey"));
            String renderedPrompt = promptTemplateService.render(template, renderVariables);

            Map<String, Object> providerInput = new LinkedHashMap<>(inputPayload);
            providerInput.put("renderedPrompt", renderedPrompt);
            CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                    script.getTenantId(),
                    script.getUserId(),
                    script.getProjectId(),
                    generationJobId,
                    null
            );
            CreatorAiService.MeteredAiResponse aiResponse;
            try {
                aiResponse = generateMeteredWithRetry(promptType, providerInput, usageContext, script, shotNumber, rootKey);
            } catch (RuntimeException providerEx) {
                if (isTransientProviderFailure(providerEx)) {
                    return fallbackGeneratedTag(
                            promptType, rootKey, script, generationJobId, template, renderedPrompt,
                            inputPayload, schemaReference, providerEx, shotNumber, AI_TRANSIENT_MAX_ATTEMPTS
                    );
                }
                throw providerEx;
            }
            Map<String, Object> providerOutput = aiResponse.output();
            providerOutputForDebug = providerOutput == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providerOutput);
            Map<String, Object> tag = requireCompleteTagPayload(
                    promptType,
                    rootKey,
                    providerOutput,
                    aiResponse.tokenMetadata(),
                    schemaReference,
                    script,
                    shotNumber
            );

            Map<String, Object> outputPayload = new LinkedHashMap<>();
            outputPayload.put(rootKey, tag);
            outputPayload.put("providerOutput", providerOutput);
            outputPayload.put("validation", Map.of("strictSchemaAccepted", true));

            CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                    .tenantId(script.getTenantId())
                    .userId(script.getUserId())
                    .projectId(script.getProjectId())
                    .jobId(generationJobId)
                    .promptTemplateId(template.getId())
                    .promptTemplateKey(template.getTemplateKey())
                    .promptTemplateVersion(template.getVersion())
                    .renderedPrompt(renderedPrompt)
                    .inputSnapshot(inputPayload)
                    .provider(creatorAiService.providerName())
                    .model(creatorAiService.modelName())
                    .outputPayload(outputPayload)
                    .tokenMetadata(aiResponse.tokenMetadata())
                    .costMetadata(aiResponse.costMetadata())
                    .status("COMPLETED")
                    .completedAt(OffsetDateTime.now())
                    .build());
            creatorAiService.publishBillingDebit(promptType, aiResponse, usageContext.withPromptRunId(promptRun.getId()));
            return new GeneratedTag(tag, promptRun.getId());
        } catch (RuntimeException ex) {
            log.error("Production plan tag generation failed promptType={} scriptId={} shotNumber={} reason={}",
                    promptType,
                    script == null ? null : script.getId(),
                    shotNumber,
                    ex.getMessage());
            if (ex instanceof ResponseStatusException) {
                throw ex;
            }
            throw new CreatorAiOutputException(
                    HttpStatus.BAD_GATEWAY,
                    productionPlanFailureMessage(rootKey, script, shotNumber, List.of(), ex.getMessage()),
                    rawPromptDebugPayload(promptType, rootKey, providerOutputForDebug, defaultString(ex.getMessage(), ex.getClass().getSimpleName()), List.of())
            );
        }
    }

    private CreatorAiService.MeteredAiResponse generateMeteredWithRetry(
            String promptType,
            Map<String, Object> providerInput,
            CreatorAiService.AiUsageContext usageContext,
            CreatorScript script,
            int shotNumber,
            String rootKey
    ) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= AI_TRANSIENT_MAX_ATTEMPTS; attempt++) {
            try {
                return creatorAiService.generateMetered(promptType, providerInput, usageContext);
            } catch (RuntimeException ex) {
                lastException = ex;
                if (!isTransientProviderFailure(ex) || attempt >= AI_TRANSIENT_MAX_ATTEMPTS) {
                    throw ex;
                }
                log.warn(
                        "Transient creator AI provider failure promptType={} scriptId={} shotNumber={} rootKey={} attempt={}/{} reason={}",
                        promptType,
                        script == null ? null : script.getId(),
                        shotNumber,
                        rootKey,
                        attempt,
                        AI_TRANSIENT_MAX_ATTEMPTS,
                        transientFailureReason(ex)
                );
                sleepBeforeRetry(attempt);
            }
        }
        throw lastException == null
                ? new IllegalStateException("Creator AI provider failed without an exception")
                : lastException;
    }

    private GeneratedTag fallbackGeneratedTag(
            String promptType,
            String rootKey,
            CreatorScript script,
            UUID generationJobId,
            CreatorPromptTemplate template,
            String renderedPrompt,
            Map<String, Object> inputPayload,
            Map<String, Object> schemaReference,
            RuntimeException providerEx,
            int shotNumber,
            int attempts
    ) {
        String reason = transientFailureReason(providerEx);
        Map<String, Object> fallbackTag = new LinkedHashMap<>(schemaReference == null ? Map.of() : schemaReference);
        fallbackTag.put("_providerFallback", true);
        fallbackTag.put("_fallbackReason", reason);
        fallbackTag.put("_fallbackAttempts", attempts);
        fallbackTag.put("_fallbackGeneratedAt", OffsetDateTime.now().toString());

        Map<String, Object> providerOutput = new LinkedHashMap<>();
        providerOutput.put("provider", creatorAiService.providerName());
        providerOutput.put("model", creatorAiService.modelName());
        providerOutput.put("promptType", promptType);
        providerOutput.put("status", "provider_transient_failure");
        providerOutput.put("error", reason);
        providerOutput.put("attempts", attempts);

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("strictSchemaAccepted", true);
        validation.put("providerFallback", true);
        validation.put("failureReason", "AI_PROVIDER_TRANSIENT_FAILURE");
        validation.put("attempts", attempts);

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put(rootKey, fallbackTag);
        outputPayload.put("providerOutput", providerOutput);
        outputPayload.put("validation", validation);

        CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .jobId(generationJobId)
                .promptTemplateId(template.getId())
                .promptTemplateKey(template.getTemplateKey())
                .promptTemplateVersion(template.getVersion())
                .renderedPrompt(renderedPrompt)
                .inputSnapshot(inputPayload)
                .provider(creatorAiService.providerName())
                .model(creatorAiService.modelName())
                .outputPayload(outputPayload)
                .tokenMetadata(new LinkedHashMap<>())
                .costMetadata(new LinkedHashMap<>())
                .status("PROVIDER_FALLBACK")
                .errorMessage(reason)
                .completedAt(OffsetDateTime.now())
                .build());

        log.warn(
                "Using deterministic production plan fallback promptType={} scriptId={} shotNumber={} rootKey={} attempts={} reason={}",
                promptType,
                script == null ? null : script.getId(),
                shotNumber,
                rootKey,
                attempts,
                reason
        );
        return new GeneratedTag(fallbackTag, promptRun.getId());
    }

    private boolean isTransientProviderFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof CreatorAiOutputException) {
                return false;
            }
            if (current instanceof WebClientResponseException responseException) {
                return isTransientStatus(responseException.getStatusCode());
            }
            if (current instanceof ResponseStatusException responseStatusException) {
                return isTransientStatus(responseStatusException.getStatusCode());
            }
            if (current instanceof WebClientRequestException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && isTransientMessage(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTransientStatus(HttpStatusCode statusCode) {
        if (statusCode == null) {
            return false;
        }
        int value = statusCode.value();
        return value == 408 || value == 429 || statusCode.is5xxServerError();
    }

    private boolean isTransientMessage(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("503")
                || normalized.contains("502")
                || normalized.contains("504")
                || normalized.contains("service unavailable")
                || normalized.contains("too many requests")
                || normalized.contains("rate limit")
                || normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused");
    }

    private String transientFailureReason(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return ex == null ? "Creator AI provider was temporarily unavailable." : ex.getClass().getSimpleName();
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(1500L, 250L * attempt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during creator AI retry backoff", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractTagPayload(Map<String, Object> providerOutput, String rootKey) {
        if (providerOutput == null || providerOutput.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> direct = extractTagPayloadFromStructured(providerOutput, rootKey);
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> rawTextPayload = rawTextJsonMap(providerOutput.get("rawText"));
        if (!rawTextPayload.isEmpty()) {
            return extractTagPayloadFromStructured(rawTextPayload, rootKey);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractTagPayloadFromStructured(Map<String, Object> providerOutput, String rootKey) {
        if (providerOutput == null || providerOutput.isEmpty()) {
            return Map.of();
        }
        Object nested = providerOutput.get(rootKey);
        if (nested instanceof Map<?, ?> nestedMap) {
            Map<String, Object> result = objectMapper.convertValue(nestedMap, new TypeReference<Map<String, Object>>() {
            });
            result.put("_usedAiOutput", true);
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>(providerOutput);
        result.remove("provider");
        result.remove("model");
        result.remove("promptType");
        result.remove("status");
        result.remove("responseId");
        result.remove("tokenUsage");
        result.remove("promptFeedback");
        result.remove("rawText");
        result.remove("rawTextPreview");
        result.remove("rawTextLength");
        result.remove("responseStatus");
        result.remove("finishReason");
        result.remove("finishReasons");
        result.remove("configuredMaxOutputTokens");
        result.remove("timeoutMs");
        result.remove("incompleteDetails");
        if (result.size() > 3) {
            result.put("_usedAiOutput", true);
            return result;
        }
        return Map.of();
    }

    private Map<String, Object> requireCompleteTagPayload(
            String promptType,
            String rootKey,
            Map<String, Object> providerOutput,
            Map<String, Object> tokenMetadata,
            Map<String, Object> schemaReference,
            CreatorScript script,
            int shotNumber
    ) {
        Map<String, Object> tag = extractTagPayload(providerOutput, rootKey);
        boolean usedAiOutput = Boolean.TRUE.equals(tag.get("_usedAiOutput"));
        tag.remove("_usedAiOutput");
        if (!usedAiOutput || tag.isEmpty()) {
            List<String> schemaIssues = List.of(rootKey);
            Map<String, Object> debugPayload = rawPromptDebugPayload(
                    promptType,
                    rootKey,
                    providerOutput,
                    "NO_PARSEABLE_TAG_JSON",
                    schemaIssues,
                    tokenMetadata,
                    tag
            );
            logAiOutputValidationFailure(promptType, rootKey, script, shotNumber, debugPayload);
            throw new CreatorAiOutputException(
                    HttpStatus.BAD_GATEWAY,
                    productionPlanFailureMessage(rootKey, script, shotNumber, schemaIssues, "AI returned no parseable " + rootKey + " JSON object."),
                    debugPayload
            );
        }

        List<String> missing = missingSchemaKeys(tag, schemaReference, "");
        missing.addAll(tagBusinessRuleViolations(rootKey, tag));
        if (!missing.isEmpty()) {
            Map<String, Object> debugPayload = rawPromptDebugPayload(
                    promptType,
                    rootKey,
                    providerOutput,
                    "INCOMPLETE_TAG_JSON",
                    missing,
                    tokenMetadata,
                    tag
            );
            logAiOutputValidationFailure(promptType, rootKey, script, shotNumber, debugPayload);
            throw new CreatorAiOutputException(
                    HttpStatus.BAD_GATEWAY,
                    productionPlanFailureMessage(rootKey, script, shotNumber, missing, "AI returned incomplete " + promptType + " JSON."),
                    debugPayload
            );
        }
        return new LinkedHashMap<>(tag);
    }

    @SuppressWarnings("unchecked")
    private List<String> missingSchemaKeys(Map<String, Object> generated, Map<String, Object> schema, String path) {
        if (schema == null || schema.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.startsWith("_")) {
                continue;
            }
            String childPath = path.isBlank() ? key : path + "." + key;
            if (generated == null || !generated.containsKey(key) || generated.get(key) == null) {
                missing.add(childPath);
                continue;
            }
            Object expected = entry.getValue();
            Object actual = generated.get(key);
            if (expected instanceof Map<?, ?> expectedMap) {
                if (!(actual instanceof Map<?, ?> actualMap)) {
                    missing.add(childPath);
                    continue;
                }
                missing.addAll(missingSchemaKeys(
                        objectMapper.convertValue(actualMap, new TypeReference<Map<String, Object>>() {
                        }),
                        objectMapper.convertValue(expectedMap, new TypeReference<Map<String, Object>>() {
                        }),
                        childPath
                ));
            } else if (expected instanceof List<?> && !(actual instanceof List<?>)) {
                missing.add(childPath);
            }
        }
        return missing;
    }

    private List<String> tagBusinessRuleViolations(String rootKey, Map<String, Object> tag) {
        List<String> violations = new ArrayList<>();
        if ("storyboardTag".equals(rootKey)) {
            violations.addAll(nonNumericFields(tag, List.of(
                    "startTimeSeconds",
                    "endTimeSeconds",
                    "durationSeconds",
                    "fps",
                    "peopleInFrame",
                    "emotionIntensity",
                    "shootDay",
                    "primaryDialogue.lineStartTime",
                    "primaryDialogue.lineEndTime"
            )));
        }
        if ("lightingBuildSheetTag".equals(rootKey)) {
            violations.addAll(nonNumericFields(tag, List.of(
                    "shotNumber",
                    "estimatedSetupMinutes",
                    "floorPlan.keyLight.distanceFeet",
                    "floorPlan.keyLight.angleDegrees",
                    "floorPlan.fillLight.distanceFeet",
                    "floorPlan.fillLight.angleDegrees",
                    "floorPlan.rimLight.distanceFeet",
                    "floorPlan.rimLight.angleDegrees",
                    "floorPlan.negFill.distanceFeet",
                    "floorPlan.negFill.angleDegrees",
                    "floorPlan.camera.distanceFeet",
                    "floorPlan.camera.heightFeet"
            )));
            if (mapList(tag.get("gearCards")).size() != 6) {
                violations.add("gearCards must contain exactly 6 cards");
            }
            if (mapList(tag.get("buildSteps")).size() < 5) {
                violations.add("buildSteps must contain at least 5 steps");
            }
        }
        if ("cameraPlanSheetTag".equals(rootKey)) {
            violations.addAll(nonNumericFields(tag, List.of(
                    "shotNumber",
                    "startTimeSeconds",
                    "endTimeSeconds",
                    "durationSeconds",
                    "fps",
                    "framePreview.headroomPercent",
                    "framePreview.leadRoomPercent",
                    "cameraRig.fps"
            )));
            if (mapList(tag.get("executionSteps")).size() < 5) {
                violations.add("executionSteps must contain at least 5 steps");
            }
        }
        return violations;
    }

    private List<String> nonNumericFields(Map<String, Object> tag, List<String> paths) {
        List<String> violations = new ArrayList<>();
        for (String path : paths) {
            Object value = valueAtPath(tag, path);
            if (value != null && !(value instanceof Number)) {
                violations.add(path + " must be numeric");
            }
        }
        return violations;
    }

    @SuppressWarnings("unchecked")
    private Object valueAtPath(Map<String, Object> source, String path) {
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
    }

    private String productionPlanFailureMessage(
            String rootKey,
            CreatorScript script,
            int shotNumber,
            List<String> missing,
            String reason
    ) {
        boolean hasSchemaIssues = missing != null && !missing.isEmpty();
        StringBuilder message = new StringBuilder()
                .append("Production plan generation failed for shot ")
                .append(shotNumber)
                .append(" (")
                .append(rootKey)
                .append("). ")
                .append(defaultString(reason, "The AI response was not valid for the required schema."))
                .append(" ScriptId=")
                .append(script == null ? "" : script.getId())
                .append(".");
        if (hasSchemaIssues) {
            String missingText = String.join(", ", missing.stream().limit(16).toList());
            message.append(" Missing or invalid fields: ")
                    .append(missingText)
                    .append(". Please add the missing screenplay details and regenerate: character continuity/wardrobe, blockingNotes, lightingMobile/lightingProfessional, soundDesign with ambient_bed and sync_hit, captionTrack, safetyFlags, resourceRequirements, and postProductionNotes.");
        } else {
            message.append(" Schema fields were not the issue; retry generation or check the AI provider health/configuration.");
        }
        return message.toString();
    }

    private Map<String, Object> rawPromptDebugPayload(
            String promptType,
            String rootKey,
            Map<String, Object> providerOutput,
            String failureReason,
            List<String> schemaIssues
    ) {
        return rawPromptDebugPayload(promptType, rootKey, providerOutput, failureReason, schemaIssues, Map.of(), Map.of());
    }

    private Map<String, Object> rawPromptDebugPayload(
            String promptType,
            String rootKey,
            Map<String, Object> providerOutput,
            String failureReason,
            List<String> schemaIssues,
            Map<String, Object> tokenMetadata,
            Map<String, Object> extractedPayload
    ) {
        Map<String, Object> diagnostics = aiOutputDiagnostics(
                promptType,
                rootKey,
                providerOutput,
                failureReason,
                schemaIssues,
                tokenMetadata,
                extractedPayload
        );

        Map<String, Object> rawPromptResponse = new LinkedHashMap<>();
        rawPromptResponse.put("promptType", defaultString(promptType, ""));
        rawPromptResponse.put("provider", creatorAiService.providerName());
        rawPromptResponse.put("model", creatorAiService.modelName());
        rawPromptResponse.put("providerOutput", providerOutput == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providerOutput));
        rawPromptResponse.put("diagnostics", diagnostics);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("rawPromptResponse", rawPromptResponse);
        return debug;
    }

    private Map<String, Object> aiOutputDiagnostics(
            String promptType,
            String rootKey,
            Map<String, Object> providerOutput,
            String failureReason,
            List<String> schemaIssues,
            Map<String, Object> tokenMetadata,
            Map<String, Object> extractedPayload
    ) {
        Map<String, Object> safeProviderOutput = providerOutput == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providerOutput);
        Map<String, Object> safeTokenMetadata = tokenMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tokenMetadata);
        Map<String, Object> safePayload = extractedPayload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extractedPayload);

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("promptType", defaultString(promptType, ""));
        diagnostics.put("rootKey", defaultString(rootKey, ""));
        diagnostics.put("failureReason", defaultString(failureReason, ""));
        diagnostics.put("likelyCause", likelyAiOutputFailureCause(safeProviderOutput, safePayload, schemaIssues));
        diagnostics.put("schemaIssues", schemaIssues == null ? List.of() : schemaIssues);
        diagnostics.put("provider", creatorAiService.providerName());
        diagnostics.put("model", creatorAiService.modelName());
        diagnostics.put("providerKeys", safeProviderOutput.keySet().stream().toList());
        diagnostics.put("providerStatus", stringValue(safeProviderOutput.get("status")));
        diagnostics.put("responseStatus", stringValue(safeProviderOutput.get("responseStatus")));
        diagnostics.put("responseId", stringValue(safeProviderOutput.get("responseId")));
        diagnostics.put("finishReason", stringValue(safeProviderOutput.get("finishReason")));
        diagnostics.put("finishReasons", stringList(safeProviderOutput.get("finishReasons")));
        diagnostics.put("tokenUsage", mapValue(safeProviderOutput.get("tokenUsage")));
        diagnostics.put("tokenMetadata", safeTokenMetadata);
        diagnostics.put("maxOutputTokens", firstNonNull(safeProviderOutput.get("configuredMaxOutputTokens"), safeTokenMetadata.get("maxOutputTokens")));
        diagnostics.put("timeoutMs", safeProviderOutput.get("timeoutMs"));
        diagnostics.put("hasRawText", safeProviderOutput.get("rawText") != null);
        diagnostics.put("rawTextLength", rawTextLength(safeProviderOutput));
        diagnostics.put("rawTextPreview", rawTextPreview(safeProviderOutput));
        diagnostics.put("providerOutputPreview", truncate(toJson(safeProviderOutput), 4000));
        diagnostics.put("extractedPayloadKeys", safePayload.keySet().stream().toList());
        diagnostics.put("fieldCounts", tagFieldCounts(rootKey, safePayload));
        return diagnostics;
    }

    private void logAiOutputValidationFailure(
            String promptType,
            String rootKey,
            CreatorScript script,
            int shotNumber,
            Map<String, Object> debugPayload
    ) {
        Map<String, Object> rawPromptResponse = mapValue(debugPayload == null ? null : debugPayload.get("rawPromptResponse"));
        Map<String, Object> diagnostics = mapValue(rawPromptResponse.get("diagnostics"));
        log.warn(
                "Creator production plan AI output validation failed promptType={} rootKey={} scriptId={} shotNumber={} failureReason={} likelyCause={} finishReason={} tokenMetadata={} tokenUsage={} maxOutputTokens={} providerKeys={} extractedPayloadKeys={} fieldCounts={} schemaIssues={} rawTextLength={} rawTextPreview={} providerOutputPreview={}",
                promptType,
                rootKey,
                script == null ? null : script.getId(),
                shotNumber,
                diagnostics.get("failureReason"),
                diagnostics.get("likelyCause"),
                diagnostics.get("finishReason"),
                diagnostics.get("tokenMetadata"),
                diagnostics.get("tokenUsage"),
                diagnostics.get("maxOutputTokens"),
                diagnostics.get("providerKeys"),
                diagnostics.get("extractedPayloadKeys"),
                diagnostics.get("fieldCounts"),
                diagnostics.get("schemaIssues"),
                diagnostics.get("rawTextLength"),
                truncate(stringValue(diagnostics.get("rawTextPreview")), 1000),
                truncate(stringValue(diagnostics.get("providerOutputPreview")), 2000)
        );
    }

    private String likelyAiOutputFailureCause(Map<String, Object> providerOutput, Map<String, Object> extractedPayload, List<String> schemaIssues) {
        if (isMaxTokensFinish(providerOutput)) {
            return "MODEL_OUTPUT_TRUNCATED_BY_MAX_OUTPUT_TOKENS";
        }
        if ((extractedPayload == null || extractedPayload.isEmpty()) && providerOutput != null && providerOutput.get("rawText") != null) {
            return "RAW_TEXT_JSON_PARSE_FAILED";
        }
        List<String> issues = schemaIssues == null ? List.of() : schemaIssues;
        if (issues.stream().anyMatch(issue -> issue.contains("gearCards") || issue.contains("buildSteps") || issue.contains("executionSteps"))) {
            return "MODEL_OMITTED_REQUIRED_ARRAY_ITEMS";
        }
        if (!issues.isEmpty()) {
            return "MODEL_OMITTED_REQUIRED_FIELDS";
        }
        return "UNKNOWN_AI_OUTPUT_VALIDATION_FAILURE";
    }

    private boolean isMaxTokensFinish(Map<String, Object> providerOutput) {
        String reason = stringValue(providerOutput == null ? null : providerOutput.get("finishReason")).toUpperCase(Locale.ROOT);
        if (reason.contains("MAX_TOKEN") || reason.contains("MAX_OUTPUT")) {
            return true;
        }
        for (String item : stringList(providerOutput == null ? null : providerOutput.get("finishReasons"))) {
            String text = item.toUpperCase(Locale.ROOT);
            if (text.contains("MAX_TOKEN") || text.contains("MAX_OUTPUT")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> tagFieldCounts(String rootKey, Map<String, Object> tag) {
        Map<String, Object> counts = new LinkedHashMap<>();
        Map<String, Object> safeTag = tag == null ? Map.of() : tag;
        if ("lightingBuildSheetTag".equals(rootKey)) {
            putListDiagnostics(counts, safeTag, "gearCards");
            putListDiagnostics(counts, safeTag, "buildSteps");
            putListDiagnostics(counts, safeTag, "safetyFlags");
        } else if ("cameraPlanSheetTag".equals(rootKey)) {
            putListDiagnostics(counts, safeTag, "executionSteps");
            putListDiagnostics(counts, safeTag, "safetyFlags");
            putListDiagnostics(counts, safeTag, "coverageSpec.companionShots");
        } else if ("storyboardTag".equals(rootKey)) {
            putListDiagnostics(counts, safeTag, "primaryCharacters");
            putListDiagnostics(counts, safeTag, "sideCharacters");
            putListDiagnostics(counts, safeTag, "culturalReferences");
        }
        return counts;
    }

    @SuppressWarnings("unchecked")
    private void putListDiagnostics(Map<String, Object> counts, Map<String, Object> source, String path) {
        Object value = valueAtPath(source, path);
        String key = path.replace('.', '_');
        counts.put(key + "Items", listSize(value));
        counts.put(key + "ObjectItems", value instanceof List<?> ? mapList(value).size() : 0);
        counts.put(key + "Type", value == null ? "missing" : value.getClass().getSimpleName());
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private int rawTextLength(Map<String, Object> providerOutput) {
        if (providerOutput == null || providerOutput.isEmpty()) {
            return 0;
        }
        Object explicitLength = providerOutput.get("rawTextLength");
        if (explicitLength != null) {
            return intValue(explicitLength, 0);
        }
        return stringValue(providerOutput.get("rawText")).length();
    }

    private String rawTextPreview(Map<String, Object> providerOutput) {
        if (providerOutput == null || providerOutput.isEmpty()) {
            return "";
        }
        String preview = stringValue(providerOutput.get("rawTextPreview"));
        if (!preview.isBlank()) {
            return preview;
        }
        return truncate(stringValue(providerOutput.get("rawText")), 4000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
    }

    private Map<String, Object> buildInputPayload(CreatorScript script, Map<String, Object> projectContext, Map<String, Object> shot, String styleKey) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scriptId", script.getId().toString());
        input.put("lockedIdeaId", stringValue(script.getLockedIdeaId()));
        input.put("storyIdeaId", stringValue(script.getStoryIdeaId()));
        input.put("shotNumber", intValue(shot.get("shotNumber"), 0));
        input.put("shot", shot);
        input.put("screenplay", script.getScriptPayload() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(script.getScriptPayload()));
        input.put("screenplayJson", script.getScriptPayload() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(script.getScriptPayload()));
        input.put("screenplayShots", safeShots(script, null));
        input.put("focusedShotNumber", intValue(shot.get("shotNumber"), 0));
        input.put("projectContext", projectContext);
        input.put("styleKey", styleKey);
        return input;
    }

    private Map<String, Object> buildProjectContext(CreatorScript script, Map<String, Object> payload, String styleKey) {
        Map<String, Object> source = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("projectTitle", defaultString(script.getTitle(), stringValue(source.get("projectTitle"))));
        context.put("characterVoiceProfiles", mapValue(source.get("characterVoiceProfiles")));
        context.put("continuityBible", mapValue(source.get("continuityBible")));
        context.put("formatTier", defaultString(script.getFormatTier(), stringValue(source.get("formatTier"), "short_form")));
        context.put("budgetTier", defaultString(script.getBudgetTier(), stringValue(source.get("budgetTier"), "zero_budget")));
        context.put("dialogueLanguage", defaultString(script.getDialogueLanguage(), stringValue(source.get("dialogueLanguage"), "English")));
        context.put("screenType", defaultString(script.getScreenType(), stringValue(source.get("screenType"), "vertical")));
        context.put("directorInitials", defaultString(stringValue(source.get("directorInitials")), "DL"));
        context.put("styleKey", styleKey);
        context.put("characterCastMappings", mapList(source.get("characterCastMappings")));
        context.put("availableActors", mapList(source.get("availableActors")));
        context.put("audienceDecision", mapValue(source.get("audienceDecision")));
        context.put("brandContext", mapValue(source.get("brandContext")));
        context.put("creatorContext", mapValue(source.get("creatorContext")));
        context.put("resourceRequirements", mapValue(source.get("resourceRequirements")));
        context.put("soundDesignPlan", mapValue(source.get("soundDesignPlan")));
        context.put("backgroundMusicPlan", mapValue(source.get("backgroundMusicPlan")));
        context.put("storyCharacters", mapList(source.get("storyCharacters")));
        context.put("storyBeats", mapList(source.get("storyBeats")));
        return context;
    }

    private Map<String, Object> storyboardTagSchemaReference(CreatorScript script, Map<String, Object> context, Map<String, Object> shot) {
        double start = timeValue(shot.get("startTime"), 0d);
        double duration = timeValue(shot.get("durationSeconds"), Math.max(1d, timeValue(shot.get("endTime"), start + 3d) - start));
        double end = timeValue(shot.get("endTime"), start + duration);
        List<String> primaryNames = stringList(shot.get("primaryCharacters"));
        List<String> sideNames = stringList(shot.get("sideCharacters"));
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("projectTitle", defaultString(stringValue(context.get("projectTitle")), defaultString(script.getTitle(), "")));
        tag.put("sequenceTitle", defaultString(stringValue(shot.get("sequenceTitle")), ""));
        tag.put("sceneLocation", defaultString(firstString(shot.get("environment"), shot.get("setDesign")), ""));
        tag.put("directorInitials", defaultString(stringValue(context.get("directorInitials")), "DL"));
        tag.put("shotTitle", defaultString(stringValue(shot.get("title")), "Shot " + intValue(shot.get("shotNumber"), 0)));
        tag.put("beatTitle", defaultString(firstString(shot.get("beatTitle"), shot.get("narrativeBeat")), ""));
        tag.put("narrativeBeatSummary", defaultString(firstString(shot.get("narrativeBeat"), shot.get("purpose")), ""));
        tag.put("startTimeSeconds", start);
        tag.put("endTimeSeconds", end);
        tag.put("durationSeconds", duration);
        tag.put("shotType", shotTypeCode(shot.get("shotType")));
        tag.put("shotTypeFullName", shotTypeFullName(shot.get("shotType")));
        tag.put("cameraAngle", defaultString(stringValue(shot.get("cameraAngle")), "Eye Level"));
        tag.put("cameraMovement", cameraMovement(shot.get("cameraMovement")));
        tag.put("lensSuggestion", defaultString(stringValue(shot.get("lensSuggestion")), "Mobile 1x Wide"));
        tag.put("fps", intValue(firstNonNull(shot.get("fps"), nested(shot, "cinematicExecution", "recommendedFPS")), 24));
        tag.put("coverageType", coverageType(shot.get("coverageType")));
        tag.put("screenType", normalizeScreenType(context.get("screenType")));
        tag.put("screenDirection", screenDirection(shot.get("screenDirection")));
        tag.put("compositionSummary", defaultString(stringValue(shot.get("composition")), "Character centered, eyes near upper third"));
        tag.put("headroomNote", "Headroom");
        tag.put("frameLeftNote", "Frame Left: " + defaultString(firstString(shot.get("environment"), shot.get("setDesign")), "environment anchor"));
        tag.put("frameRightNote", "Frame Right: " + defaultString(stringValue(shot.get("textOverlay")), "negative space"));
        tag.put("targetFocalPoint", "TARGET: " + defaultString(firstString(shot.get("retentionGoal"), shot.get("action")), "main character reaction"));
        tag.put("primaryCharacters", characterSpecs(primaryNames, context));
        tag.put("sideCharacters", characterSpecs(sideNames, context));
        tag.put("peopleInFrame", intValue(shot.get("peopleInFrame"), primaryNames.size() + sideNames.size()));
        tag.put("setDesign", defaultString(stringValue(shot.get("setDesign")), ""));
        tag.put("environment", defaultString(stringValue(shot.get("environment")), ""));
        tag.put("sceneTimeOfDay", sceneTimeOfDay(shot));
        tag.put("culturalReferences", culturalReferences(shot));
        tag.put("lightingAtmosphericDescription", defaultString(firstString(shot.get("lighting"), shot.get("lightingMobile")), "soft visible light on face with readable shadows"));
        tag.put("keyLightSourceLabel", keyLightLabel(shot));
        tag.put("expression", defaultString(stringValue(shot.get("expression")), ""));
        tag.put("emotion", defaultString(stringValue(shot.get("emotion")), ""));
        tag.put("emotionIntensity", doubleValue(shot.get("emotionIntensity"), 0d));
        tag.put("bodyLanguage", defaultString(stringValue(shot.get("bodyLanguage")), ""));
        tag.put("action", defaultString(firstString(shot.get("action"), shot.get("primaryActorAction")), ""));
        tag.put("dialogueLanguage", defaultString(stringValue(context.get("dialogueLanguage")), "English"));
        tag.put("primaryDialogue", primaryDialogue(shot, context));
        tag.put("textOverlay", defaultString(stringValue(shot.get("textOverlay")), ""));
        tag.put("textOverlayEmoji", textOverlayEmoji(shot, context));
        tag.put("captionStyle", captionStyle(shot, context));
        tag.put("ambientBedDescription", defaultString(firstString(
                shot.get("ambientBedDescription"),
                soundLayerDescription(shot.get("soundDesign"), "ambient_bed"),
                firstListValue(shot.get("soundDesign"), "")
        ), "room tone"));
        tag.put("syncHitDescription", defaultString(firstString(
                shot.get("syncHitDescription"),
                soundLayerDescription(shot.get("soundDesign"), "sync_hit")
        ), ""));
        tag.put("transitionNote", defaultString(stringValue(shot.get("transition")), ""));
        tag.put("shootDay", intValue(shot.get("shootDay"), 1));
        tag.put("shootBlock", defaultString(stringValue(shot.get("shootBlock")), "morning"));
        tag.put("budgetTier", budgetTier(context));
        tag.put("formatTier", formatTier(context));
        tag.put("directorNote", defaultString(firstString(shot.get("directorNotes"), shot.get("creatorDirection")), "Keep the performance specific and readable in one frame."));
        tag.put("creatorTip", creatorTip(context, shot));
        tag.put("imageGenerationPromptOverride", explicitOverride(shot));
        return tag;
    }

    private Map<String, Object> lightingBuildSheetTagSchemaReference(CreatorScript script, Map<String, Object> context, Map<String, Object> shot) {
        String budgetTier = budgetTier(context);
        boolean zero = budgetTier.equals("zero_budget") || budgetTier.equals("micro_budget");
        boolean indie = budgetTier.equals("indie");
        int setupMinutes = zero ? 10 : indie ? 22 : 45;
        Map<String, Object> key = lightPlacement("KEY", zero ? "Yellow desk lamp" : indie ? "Godox SL60 with softbox" : "Aputure 600d Pro through Magic Cloth", "ARRI SkyPanel S60-C at 5600K with Lite Grid", "front-left at 45 degrees", 3.0d, 45.0d, zero ? "white bedsheet diffuser" : "softbox");
        Map<String, Object> fill = lightPlacement("FILL", zero ? "White A4 paper as bounce card" : indie ? "Small LED panel" : "4x4 ultrabounce", "4x4 ultrabounce", "front-right low intensity", 4.0d, 30.0d, "bounce");
        Map<String, Object> rim = lightPlacement("RIM", zero ? "Phone flashlight wrapped in white plastic carry bag" : indie ? "LED stick light" : "Astera Titan tube", "Aputure 300d through fresnel", "behind subject right shoulder", 5.0d, 145.0d, zero ? "white plastic bag diffusion" : "light diffusion");
        Map<String, Object> neg = lightPlacement("NEG", zero ? "Black bedsheet draped on chair" : indie ? "Black cloth on stand" : "4x4 solid floppy", "Duvetyne on C-stand", "camera-right close to shadow side", 2.0d, 90.0d, "black fabric");

        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("projectTitle", defaultString(stringValue(context.get("projectTitle")), defaultString(script.getTitle(), "")));
        tag.put("shotTitle", defaultString(stringValue(shot.get("title")), "Shot " + intValue(shot.get("shotNumber"), 0)));
        tag.put("shotNumber", intValue(shot.get("shotNumber"), 0));
        tag.put("cinematicIntent", defaultString(firstString(shot.get("lighting"), shot.get("lightingProfessional")), "Soft motivated key light with readable shadow contrast and a clean creator-friendly frame."));
        tag.put("budgetTier", budgetTier);
        tag.put("estimatedSetupMinutes", setupMinutes);
        tag.put("directorInitials", defaultString(stringValue(context.get("directorInitials")), "DL"));
        Map<String, Object> floorPlan = new LinkedHashMap<>();
        floorPlan.put("roomDescription", roomDescription(shot));
        floorPlan.put("actor", Map.of("characterName", firstCharacterName(shot), "facingDirection", facingDirection(shot)));
        floorPlan.put("keyLight", key);
        floorPlan.put("fillLight", fill);
        floorPlan.put("rimLight", rim);
        floorPlan.put("negFill", neg);
        floorPlan.put("camera", Map.of("rigDescription", cameraRigName(budgetTier), "distanceFeet", cameraDistance(shot), "heightFeet", cameraHeight(shot)));
        floorPlan.put("compassNote", "window faces north; camera looks south");
        tag.put("floorPlan", floorPlan);
        tag.put("perspectiveView", Map.of(
                "narrativeDescription", perspectiveLightingDescription(shot, key, fill, rim, neg),
                "visibleElements", List.of(key.get("householdGearName"), fill.get("householdGearName"), rim.get("householdGearName"), neg.get("householdGearName"), cameraRigName(budgetTier))
        ));
        tag.put("gearCards", lightingGearCards(budgetTier, key, fill, rim, neg));
        tag.put("buildSteps", lightingBuildSteps(setupMinutes));
        tag.put("imageGenerationPromptOverride", explicitOverride(shot));
        return tag;
    }

    private Map<String, Object> cameraPlanSheetTagSchemaReference(CreatorScript script, Map<String, Object> context, Map<String, Object> shot) {
        String budgetTier = budgetTier(context);
        double start = timeValue(shot.get("startTime"), 0d);
        double duration = timeValue(shot.get("durationSeconds"), Math.max(1d, timeValue(shot.get("endTime"), start + 3d) - start));
        double end = timeValue(shot.get("endTime"), start + duration);
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("projectTitle", defaultString(stringValue(context.get("projectTitle")), defaultString(script.getTitle(), "")));
        tag.put("shotNumber", intValue(shot.get("shotNumber"), 0));
        tag.put("shotTitle", defaultString(stringValue(shot.get("title")), "Shot " + intValue(shot.get("shotNumber"), 0)));
        tag.put("startTimeSeconds", start);
        tag.put("endTimeSeconds", end);
        tag.put("durationSeconds", duration);
        tag.put("fps", intValue(firstNonNull(shot.get("fps"), nested(shot, "cinematicExecution", "recommendedFPS")), 24));
        tag.put("shotType", shotTypeCode(shot.get("shotType")));
        tag.put("cameraAngle", defaultString(stringValue(shot.get("cameraAngle")), "Eye Level"));
        tag.put("cameraMovement", cameraMovement(shot.get("cameraMovement")));
        tag.put("lensSuggestion", defaultString(stringValue(shot.get("lensSuggestion")), "Mobile 1x Wide"));
        tag.put("coverageType", coverageType(shot.get("coverageType")));
        tag.put("screenType", normalizeScreenType(context.get("screenType")));
        tag.put("screenDirection", screenDirection(shot.get("screenDirection")));
        tag.put("directorInitials", defaultString(stringValue(context.get("directorInitials")), "DL"));
        tag.put("blockingMap", blockingMap(shot, context));
        tag.put("framePreview", framePreview(shot, context));
        tag.put("cameraRig", cameraRig(budgetTier, shot));
        tag.put("movementSpec", movementSpec(budgetTier, shot));
        tag.put("coverageSpec", coverageSpec(shot));
        tag.put("executionSteps", cameraExecutionSteps(shot));
        List<String> safetyFlags = stringList(shot.get("safetyFlags"));
        tag.put("safetyFlags", safetyFlags.isEmpty() ? List.of("none") : safetyFlags);
        tag.put("requiresCoordinator", requiresCoordinator(safetyFlags));
        tag.put("complianceNote", requiresCoordinator(safetyFlags) ? "Confirm permit, insurance, consent, and coordinator coverage before roll." : "");
        tag.put("directorNote", defaultString(firstString(shot.get("directorNotes"), shot.get("creatorDirection")), "Frame the action cleanly and protect continuity for the edit."));
        tag.put("imageGenerationPromptOverride", explicitOverride(shot));
        return tag;
    }

    private List<ShotProductionPlanTagResponse> toResponses(List<CreatorScriptShotPlan> plans) {
        return plans.stream().map(this::toResponse).toList();
    }

    public ShotProductionPlanTagResponse toResponse(CreatorScriptShotPlan plan) {
        return new ShotProductionPlanTagResponse(
                plan.getId(),
                plan.getShotNumber(),
                plan.getStyleKey(),
                mapValue(plan.getStoryboardTag()),
                mapValue(plan.getLightingBuildSheetTag()),
                mapValue(plan.getCameraPlanSheetTag()),
                mapValue(plan.getPromptRunIds()),
                rawPromptResponses(mapValue(plan.getPromptRunIds())),
                plan.getUpdatedAt()
        );
    }

    private Map<String, Object> rawPromptResponses(Map<String, Object> promptRunIds) {
        Map<String, Object> responses = new LinkedHashMap<>();
        if (promptRunIds == null || promptRunIds.isEmpty()) {
            return responses;
        }
        promptRunIds.forEach((key, value) -> {
            UUID promptRunId = uuidValue(value);
            if (promptRunId == null) {
                return;
            }
            promptRunRepository.findById(promptRunId).ifPresent(promptRun -> {
                String responseKey = defaultString(key, "promptRun").replace("PromptRunId", "RawPromptResponse");
                responses.put(responseKey, rawPromptResponse(promptRun));
            });
        });
        return responses;
    }

    private Map<String, Object> rawPromptResponse(CreatorPromptRun promptRun) {
        Map<String, Object> outputPayload = mapValue(promptRun == null ? null : promptRun.getOutputPayload());
        Map<String, Object> response = new LinkedHashMap<>();
        if (promptRun == null) {
            return response;
        }
        response.put("promptRunId", promptRun.getId() == null ? "" : promptRun.getId().toString());
        response.put("promptType", defaultString(promptRun.getPromptTemplateKey(), ""));
        response.put("provider", defaultString(promptRun.getProvider(), ""));
        response.put("model", defaultString(promptRun.getModel(), ""));
        response.put("status", defaultString(promptRun.getStatus(), ""));
        response.put("providerOutput", mapValue(outputPayload.get("providerOutput")));
        response.put("validation", mapValue(outputPayload.get("validation")));
        if (promptRun.getErrorMessage() != null && !promptRun.getErrorMessage().isBlank()) {
            response.put("errorMessage", promptRun.getErrorMessage());
        }
        return response;
    }

    private List<Map<String, Object>> safeShots(CreatorScript script, List<Map<String, Object>> shotPayloads) {
        if (shotPayloads != null && !shotPayloads.isEmpty()) {
            return shotPayloads;
        }
        if (script != null && script.getShots() != null) {
            return script.getShots();
        }
        return List.of();
    }

    private List<Map<String, Object>> characterSpecs(List<String> names, Map<String, Object> context) {
        return names.stream().map(name -> characterSpec(name, context)).toList();
    }

    private Map<String, Object> characterSpec(String characterName, Map<String, Object> context) {
        Map<String, Object> continuity = continuityFor(characterName, context);
        Map<String, Object> actor = actorFor(characterName, context);
        Map<String, Object> hair = mapValue(continuity.get("hair"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("storyCharacterName", characterName);
        spec.put("archetypeLabel", archetypeLabel(characterName, continuity));
        spec.put("age", intValue(continuity.get("age"), 0));
        spec.put("gender", defaultString(stringValue(continuity.get("gender")), ""));
        spec.put("ethnicity", defaultString(stringValue(continuity.get("ethnicity")), ""));
        spec.put("bodyType", defaultString(stringValue(continuity.get("bodyType")), ""));
        spec.put("heightImpression", defaultString(stringValue(continuity.get("heightImpression")), ""));
        spec.put("hair", Map.of(
                "style", defaultString(stringValue(hair.get("style")), ""),
                "length", defaultString(stringValue(hair.get("length")), ""),
                "color", defaultString(stringValue(hair.get("color")), "")
        ));
        spec.put("distinguishingFeatures", defaultString(firstString(continuity.get("distinguishingFeatures"), continuity.get("features")), ""));
        spec.put("wardrobeThisShot", defaultString(firstString(continuity.get("wardrobeThisShot"), continuity.get("wardrobeBaseline")), ""));
        spec.put("postureBaseline", defaultString(stringValue(continuity.get("postureBaseline")), ""));
        spec.put("assignedActorName", defaultString(firstString(actor.get("actorName"), actor.get("castDisplayName"), actor.get("displayName"), actor.get("name")), ""));
        spec.put("assignedActorVisualProfile", defaultString(firstString(actor.get("assignedActorVisualProfile"), actor.get("profile"), actor.get("look")), ""));
        return spec;
    }

    private Map<String, Object> primaryDialogue(Map<String, Object> shot, Map<String, Object> context) {
        Map<String, Object> dialogue = mapValue(shot.get("dialogue"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("characterName", "");
        result.put("archetypeLabel", "");
        result.put("line", "");
        result.put("deliveryNote", "");
        result.put("subtext", "");
        result.put("lineStartTime", 0.0d);
        result.put("lineEndTime", 0.0d);
        if (dialogue.isEmpty()) {
            return result;
        }
        String speaker = dialogue.keySet().stream().map(String::valueOf).sorted().findFirst().orElse("");
        Object rawLine = dialogue.get(speaker);
        Map<String, Object> lineMap = firstDialogueLine(rawLine);
        result.put("characterName", speaker);
        result.put("archetypeLabel", archetypeLabel(speaker, continuityFor(speaker, context)));
        result.put("line", defaultString(firstString(lineMap.get("line"), lineMap.get("text"), rawLine), ""));
        result.put("deliveryNote", defaultString(stringValue(lineMap.get("deliveryNote")), ""));
        result.put("subtext", defaultString(stringValue(lineMap.get("subtext")), ""));
        result.put("lineStartTime", timeValue(lineMap.get("lineStartTime"), timeValue(shot.get("startTime"), 0d)));
        result.put("lineEndTime", timeValue(lineMap.get("lineEndTime"), timeValue(shot.get("endTime"), 0d)));
        return result;
    }

    private Map<String, Object> firstDialogueLine(Object rawLine) {
        if (rawLine instanceof List<?> list && !list.isEmpty()) {
            return mapValue(list.get(0));
        }
        if (rawLine instanceof Map<?, ?>) {
            return mapValue(rawLine);
        }
        return Map.of("line", stringValue(rawLine));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> continuityFor(String characterName, Map<String, Object> context) {
        Map<String, Object> bible = mapValue(context.get("continuityBible"));
        Object exact = bible.get(characterName);
        if (exact instanceof Map<?, ?>) {
            return objectMapper.convertValue(exact, new TypeReference<Map<String, Object>>() {
            });
        }
        for (Map<String, Object> character : mapList(context.get("storyCharacters"))) {
            String name = defaultString(firstString(character.get("name"), character.get("characterName")), "");
            if (name.equalsIgnoreCase(defaultString(characterName, ""))) {
                return character;
            }
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> actorFor(String characterName, Map<String, Object> context) {
        for (Map<String, Object> mapping : mapList(context.get("characterCastMappings"))) {
            String mappedName = defaultString(firstString(mapping.get("characterName"), mapping.get("storyCharacterName")), "");
            if (mappedName.equalsIgnoreCase(defaultString(characterName, ""))) {
                Map<String, Object> result = new LinkedHashMap<>(mapping);
                result.putAll(mapValue(mapping.get("actor")));
                result.putAll(mapValue(mapping.get("castPayload")));
                return result;
            }
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> blockingMap(Map<String, Object> shot, Map<String, Object> context) {
        List<String> characters = new ArrayList<>();
        characters.addAll(stringList(shot.get("primaryCharacters")));
        characters.addAll(stringList(shot.get("sideCharacters")));
        List<Map<String, Object>> actors = characters.stream().map(name -> {
            Map<String, Object> continuity = continuityFor(name, context);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("characterName", name);
            item.put("age", intValue(continuity.get("age"), 0));
            item.put("heightImpression", defaultString(stringValue(continuity.get("heightImpression")), ""));
            item.put("startPosition", defaultString(stringValue(shot.get("blockingNotes")), "center mark, facing camera"));
            item.put("endPosition", defaultString(stringValue(shot.get("blockingNotes")), "center mark, facing camera"));
            item.put("movementPath", isStaticMovement(shot.get("cameraMovement")) ? "static" : defaultString(stringValue(shot.get("primaryActorAction")), "moves through frame"));
            item.put("movementDistanceFeet", isStaticMovement(shot.get("cameraMovement")) ? 0.0d : 3.0d);
            return item;
        }).toList();
        if (actors.isEmpty()) {
            actors = List.of(Map.of("characterName", "", "age", 0, "heightImpression", "", "startPosition", "", "endPosition", "", "movementPath", "static", "movementDistanceFeet", 0.0d));
        }
        double distance = cameraDistance(shot);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("actors", actors);
        map.put("cameraStartEnd", Map.of(
                "startPosition", "stage-right, " + distance + " ft from subject, " + cameraAngleHeightPhrase(shot),
                "endPosition", "stage-right, " + distance + " ft from subject, " + cameraAngleHeightPhrase(shot),
                "movementDescription", isStaticMovement(shot.get("cameraMovement")) ? "static" : stringValue(shot.get("cameraMovement")),
                "startDistanceFeet", distance,
                "endDistanceFeet", distance
        ));
        map.put("keyProps", keyProps(shot));
        map.put("oneEightyLineNote", oneEightyLineNote(characters));
        map.put("roomDimensions", roomDimensions(shot));
        return map;
    }

    private Map<String, Object> framePreview(Map<String, Object> shot, Map<String, Object> context) {
        String screenType = normalizeScreenType(context.get("screenType"));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("aspectRatio", "horizontal".equals(screenType) ? "16:9" : "9:16");
        map.put("headroomPercent", shotTypeCode(shot.get("shotType")).equals("CU") ? 8.0d : 18.0d);
        map.put("leadRoomPercent", isStaticMovement(shot.get("cameraMovement")) ? 25.0d : 50.0d);
        map.put("subjectPlacement", defaultString(stringValue(shot.get("composition")), "center frame, eyes on upper third"));
        map.put("captionPosition", defaultString(stringValue(captionStyle(shot, context).get("position")), "bottom"));
        map.put("mobileFocusArea", "vertical".equals(screenType) ? "Main subject stays inside central 50% safe zone." : "N/A (horizontal screen)");
        map.put("lensCompressionFeel", lensCompressionFeel(shot.get("lensSuggestion")));
        return map;
    }

    private Map<String, Object> cameraRig(String budgetTier, Map<String, Object> shot) {
        boolean zero = budgetTier.equals("zero_budget") || budgetTier.equals("micro_budget");
        boolean indie = budgetTier.equals("indie");
        String lens = defaultString(stringValue(shot.get("lensSuggestion")), zero ? "Mobile 1x Wide" : "Cinema 35mm");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cameraBody", zero ? "iPhone 14 Pro" : indie ? "Sony FX3" : "ARRI Alexa Mini LF");
        map.put("lensSuggestion", lens);
        map.put("fps", intValue(firstNonNull(shot.get("fps"), nested(shot, "cinematicExecution", "recommendedFPS")), 24));
        map.put("shutterAngle", zero ? "180° (1/48s)" : "180° shutter at 24fps");
        map.put("iso", zero ? "auto" : indie ? "ISO 800 manual" : "ISO 800 base");
        map.put("aperture", zero ? "f/1.78 (1x lens fixed)" : indie ? "f/2.8" : "T2.8");
        map.put("whiteBalance", zero ? "auto" : "5600K daylight clean");
        map.put("filter", zero ? "none" : indie ? "VND filter if daylight clips" : "ND 0.9 (3-stop) + Black Pro-Mist 1/4");
        return map;
    }

    private Map<String, Object> movementSpec(String budgetTier, Map<String, Object> shot) {
        String movement = cameraMovement(shot.get("cameraMovement"));
        boolean staticMove = isStaticMovement(movement);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("moveType", movement);
        map.put("startPosition", "locked at mark, " + cameraDistance(shot) + " ft from subject");
        map.put("endPosition", staticMove ? "same as start" : "end mark after movement");
        map.put("speed", staticMove ? "" : "slow");
        map.put("stabilizationRequired", !staticMove);
        map.put("stabilizationTool", staticMove ? "none (tripod locked)" : budgetTier.equals("studio") ? "Steadicam" : "handheld with phone gimbal");
        map.put("rigType", cameraRigName(budgetTier));
        return map;
    }

    private Map<String, Object> coverageSpec(Map<String, Object> shot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("coverageType", coverageType(shot.get("coverageType")));
        map.put("coverageContext", "Single coverage for " + defaultString(stringValue(shot.get("title")), "this beat") + ".");
        map.put("companionShots", List.of());
        map.put("editorIntent", defaultString(stringValue(shot.get("retentionGoal")), "Cut here to clarify the emotional beat and maintain rhythm."));
        return map;
    }

    private List<Map<String, Object>> lightingGearCards(String budgetTier, Map<String, Object> key, Map<String, Object> fill, Map<String, Object> rim, Map<String, Object> neg) {
        boolean zero = budgetTier.equals("zero_budget") || budgetTier.equals("micro_budget");
        return List.of(
                gearCard(1, "KEY LIGHT", itemName(key, zero), List.of("Place key light 3 ft from actor at 45 degrees.", "Diffuse it with white cloth if shadows are hard.", "Aim toward actor's eyes.", "Keep it out of frame.")),
                gearCard(2, "FILL LIGHT", itemName(fill, zero), List.of("Place fill opposite key light.", "Keep it softer and weaker than key.", "Bounce into wall or paper.", "Check shadows still have shape.")),
                gearCard(3, "RIM LIGHT", itemName(rim, zero), List.of("Place rim behind actor's right shoulder.", "Diffuse phone or LED so it does not flare.", "Aim at hair or shoulder edge.", "Hide it from camera.")),
                gearCard(4, "NEGATIVE FILL", itemName(neg, zero), List.of("Hang dark cloth on shadow side.", "Keep it close to actor but out of frame.", "Use it to deepen cheek shadow.", "Remove if face becomes too dark.")),
                gearCard(5, "DIFFUSER", zero ? "White cotton bedsheet pinned to clothes rack" : "Softbox or Magic Cloth", List.of("Place between key and actor.", "Keep cloth flat and safe.", "Move closer for softer light.", "Do not let it enter frame.")),
                gearCard(6, "CAMERA RIG", cameraRigName(budgetTier), List.of("Set camera at planned distance.", "Keep lens at actor eye height unless angle says otherwise.", "Lock framing before lighting check.", "Shoot one test frame."))
        );
    }

    private List<Map<String, Object>> lightingBuildSteps(int totalMinutes) {
        int[] minutes = totalMinutes <= 12 ? new int[]{2, 2, 1, 1, 2, 2} : new int[]{4, 4, 3, 3, 4, totalMinutes - 18};
        return List.of(
                buildStep(1, "PLACE KEY LIGHT ( " + minutes[0] + " MIN )", "Put the key light at front-left and aim it at the actor's eyes.", minutes[0]),
                buildStep(2, "ADD FILL LIGHT ( " + minutes[1] + " MIN )", "Place fill opposite the key and keep it dimmer than the key.", minutes[1]),
                buildStep(3, "SET RIM LIGHT ( " + minutes[2] + " MIN )", "Hide the rim behind the actor and aim it at hair or shoulder edge.", minutes[2]),
                buildStep(4, "PLACE NEG FILL ( " + minutes[3] + " MIN )", "Put dark fabric on the shadow side to control spill.", minutes[3]),
                buildStep(5, "DIFFUSE KEY ( " + minutes[4] + " MIN )", "Add cloth or softbox if the face shadow looks too hard.", minutes[4]),
                buildStep(6, "CHECK FRAME", "If the lit side is clearly brighter and a thin edge highlight is visible, lighting is correct.", minutes[5])
        );
    }

    private List<Map<String, Object>> cameraExecutionSteps(Map<String, Object> shot) {
        return List.of(
                executionStep(1, "SET MARKS", "Tape actor and camera positions before rehearsal."),
                executionStep(2, "FRAME UP", "Lock the frame to " + defaultString(stringValue(shot.get("shotType")), "the planned shot size") + "."),
                executionStep(3, "FOCUS PULL", "Set focus on the primary actor's eyes and mark any movement."),
                executionStep(4, "EXPOSURE", "Set ISO, aperture, and white balance before roll."),
                executionStep(5, "REHEARSE MOVEMENT", "Walk actor and camera movement two times without recording."),
                executionStep(6, "ROLL", "Call action, protect the emotional beat, and cut after the movement settles."),
                executionStep(7, "CHECK PLAYBACK", "Review take immediately for focus, framing, and continuity.")
        );
    }

    private List<Map<String, Object>> keyProps(Map<String, Object> shot) {
        List<String> props = new ArrayList<>();
        props.addAll(extractProps(shot.get("action")));
        props.addAll(extractProps(shot.get("setDesign")));
        props.addAll(extractProps(shot.get("continuityNotes")));
        if (props.isEmpty()) {
            return List.of(keyProp("", "", ""));
        }
        return props.stream()
                .distinct()
                .limit(6)
                .map(prop -> keyProp(prop, "visible in frame for audience recognition", "TABLE"))
                .toList();
    }

    private Map<String, Object> keyProp(String propName, String placementNote, String handOrSide) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("propName", propName);
        prop.put("placementNote", placementNote);
        prop.put("handOrSide", handOrSide);
        return prop;
    }

    private List<String> extractProps(Object value) {
        String text = stringValue(value).toLowerCase(Locale.ROOT);
        List<String> known = List.of("phone", "bag", "cup", "glass", "tumbler", "book", "laptop", "dupatta", "chair", "table", "door", "mirror");
        return known.stream().filter(text::contains).toList();
    }

    private String textOverlayEmoji(Map<String, Object> shot, Map<String, Object> context) {
        if ("feature_film".equals(formatTier(context))) {
            return "";
        }
        String tone = (stringValue(shot.get("emotion")) + " " + stringValue(shot.get("inferredTone")) + " " + stringValue(shot.get("retentionGoal"))).toLowerCase(Locale.ROOT);
        if (tone.contains("romantic") || tone.contains("love")) {
            return "❤️";
        }
        if (tone.contains("shock") || tone.contains("surprise")) {
            return "😳";
        }
        if (tone.contains("urgent") || tone.contains("deadline")) {
            return "⏰";
        }
        if (tone.contains("win") || tone.contains("confidence") || tone.contains("triumph")) {
            return "💪";
        }
        if (tone.contains("funny") || tone.contains("comedy") || tone.contains("relatable")) {
            return "😅";
        }
        return "";
    }

    private Map<String, Object> captionStyle(Map<String, Object> shot, Map<String, Object> context) {
        List<Map<String, Object>> captions = mapList(shot.get("captionTrack"));
        if (!captions.isEmpty()) {
            Map<String, Object> first = captions.get(0);
            return Map.of(
                    "style", defaultString(stringValue(first.get("style")), "bold_pop"),
                    "position", defaultString(stringValue(first.get("position")), "center")
            );
        }
        String tier = formatTier(context);
        if (tier.equals("feature_film") || tier.equals("episodic") || tier.equals("long_short")) {
            return Map.of("style", "static", "position", "bottom");
        }
        return Map.of("style", "bold_pop", "position", "center");
    }

    private Map<String, Object> lightPlacement(String role, String household, String professional, String position, double distance, double angle, String modifier) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("householdGearName", household);
        map.put("professionalGearName", professional);
        map.put("position", position);
        map.put("distanceFeet", distance);
        map.put("angleDegrees", angle);
        map.put("modifier", modifier);
        return map;
    }

    private Map<String, Object> gearCard(int number, String role, String item, List<String> bullets) {
        return Map.of("cardNumber", number, "roleLabel", role, "itemName", item, "setupBullets", bullets);
    }

    private Map<String, Object> buildStep(int number, String title, String instruction, int minutes) {
        return Map.of("stepNumber", number, "title", title, "instruction", instruction, "estimatedMinutes", minutes);
    }

    private Map<String, Object> executionStep(int number, String title, String instruction) {
        return Map.of("stepNumber", number, "title", title, "instruction", instruction);
    }

    private boolean requiresCoordinator(List<String> safetyFlags) {
        List<String> serious = List.of("stunts", "fire", "firearms", "animals", "minors_on_set", "intimacy", "driving");
        return safetyFlags.stream().map(item -> item.toLowerCase(Locale.ROOT)).anyMatch(serious::contains);
    }

    private List<String> culturalReferences(Map<String, Object> shot) {
        String text = (stringValue(shot.get("setDesign")) + " " + stringValue(shot.get("environment"))).toLowerCase(Locale.ROOT);
        List<String> known = List.of("pressure cooker", "stainless steel tumbler", "agarbatti holder", "Ganesha idol", "Bajaj fan");
        return known.stream().filter(item -> text.contains(item.toLowerCase(Locale.ROOT))).toList();
    }

    private String oneEightyLineNote(List<String> characters) {
        if (characters.size() <= 1) {
            return "No 180° line concern (single subject).";
        }
        if (characters.size() == 2) {
            return "180° line runs through " + characters.get(0) + " and " + characters.get(1) + ". Camera stays on the same side throughout this exchange. Do NOT cross.";
        }
        return "180° line follows the dominant group axis. Keep camera on one side for continuity.";
    }

    private String archetypeLabel(String characterName, Map<String, Object> continuity) {
        String explicit = stringValue(firstNonNull(continuity.get("archetypeLabel"), continuity.get("archetype")));
        if (!explicit.isBlank()) {
            return explicit.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        }
        String text = (characterName + " " + stringValue(continuity.get("role"))).toLowerCase(Locale.ROOT);
        if (text.contains("maa") || text.contains("mother")) return "MAA";
        if (text.contains("bahu")) return "BAHU";
        if (text.contains("boss")) return "BOSS";
        if (text.contains("friend")) return "DOST";
        if (text.contains("teacher")) return "TEACHER";
        if (text.contains("student")) return "STUDENT";
        if (text.contains("influencer")) return "INFLUENCER";
        if (text.contains("boyfriend")) return "BOYFRIEND";
        if (text.contains("girlfriend")) return "GIRLFRIEND";
        String gender = stringValue(continuity.get("gender")).toLowerCase(Locale.ROOT);
        if (gender.contains("female") || gender.contains("woman")) return "WOMAN";
        if (gender.contains("male") || gender.contains("man")) return "MAN";
        return "MAN";
    }

    private String normalizeStyleKey(String styleKey) {
        String value = defaultString(styleKey, DEFAULT_STYLE_KEY).trim();
        return switch (value) {
            case "hybrid_photoboard", "photoreal_cinematic", "stylized_concept_art" -> value;
            default -> DEFAULT_STYLE_KEY;
        };
    }

    private String formatTier(Map<String, Object> context) {
        return defaultString(stringValue(context.get("formatTier")), "short_form");
    }

    private String budgetTier(Map<String, Object> context) {
        return defaultString(stringValue(context.get("budgetTier")), "zero_budget");
    }

    private String creatorTip(Map<String, Object> context, Map<String, Object> shot) {
        String tier = formatTier(context);
        if (tier.equals("micro_short") || tier.equals("short_form") || tier.equals("medium_form")) {
            return defaultString(firstString(nested(shot, "rookieFriendlyGuide", "editingTip"), shot.get("creatorDirection")), "Shoot one clean take, then check face, text, and action clarity.");
        }
        return "";
    }

    private String sceneTimeOfDay(Map<String, Object> shot) {
        String text = (stringValue(shot.get("sceneTimeOfDay")) + " " + stringValue(shot.get("lighting")) + " " + stringValue(shot.get("environment"))).toUpperCase(Locale.ROOT);
        for (String option : List.of("MORNING", "AFTERNOON", "EVENING", "NIGHT", "DUSK", "DAWN", "DAY")) {
            if (text.contains(option)) {
                return option;
            }
        }
        return "DAY";
    }

    private String keyLightLabel(Map<String, Object> shot) {
        String lighting = stringValue(shot.get("lighting")).toLowerCase(Locale.ROOT);
        if (lighting.contains("window")) return "Window light";
        if (lighting.contains("phone")) return "Phone screen glow";
        if (lighting.contains("street")) return "Streetlight (off-frame R)";
        if (lighting.contains("tube")) return "Tubelight (overhead)";
        return "Kitchen window key light";
    }

    private String shotTypeCode(Object value) {
        String text = stringValue(value).toLowerCase(Locale.ROOT);
        if (text.contains("extreme") && text.contains("close")) return "ECU";
        if (text.contains("close")) return "CU";
        if (text.contains("medium close")) return "MCU";
        if (text.contains("medium")) return "MS";
        if (text.contains("wide")) return "WS";
        return "CU";
    }

    private String shotTypeFullName(Object value) {
        return switch (shotTypeCode(value)) {
            case "ECU" -> "Extreme Close-Up";
            case "MCU" -> "Medium Close-Up";
            case "MS" -> "Medium Shot";
            case "WS" -> "Wide Shot";
            default -> "Close-Up";
        };
    }

    private String coverageType(Object value) {
        String text = defaultString(stringValue(value), "single_a").toLowerCase(Locale.ROOT);
        return text.replaceAll("[^a-z0-9]+", "_");
    }

    private String cameraMovement(Object value) {
        String text = defaultString(stringValue(value), "Static").trim();
        return text.isBlank() ? "Static" : text;
    }

    private String screenDirection(Object value) {
        return defaultString(stringValue(value), "static").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private boolean isStaticMovement(Object value) {
        String text = stringValue(value).toLowerCase(Locale.ROOT);
        return text.isBlank() || text.contains("static") || text.contains("locked");
    }

    private String normalizeScreenType(Object value) {
        String text = defaultString(stringValue(value), "vertical").toLowerCase(Locale.ROOT);
        return text.contains("horizontal") || text.contains("16:9") || text.contains("landscape") ? "horizontal" : "vertical";
    }

    private String roomDescription(Map<String, Object> shot) {
        return defaultString(firstString(shot.get("setDesign"), shot.get("environment")), "10x12 ft creator room with practical household set pieces");
    }

    private String roomDimensions(Map<String, Object> shot) {
        String room = roomDescription(shot);
        return room.matches(".*\\d+.*") ? room : "10x12 ft room";
    }

    private String facingDirection(Map<String, Object> shot) {
        return "facing camera, slight 3/4 turn to the left, " + screenDirection(shot.get("screenDirection"));
    }

    private String firstCharacterName(Map<String, Object> shot) {
        List<String> primary = stringList(shot.get("primaryCharacters"));
        if (!primary.isEmpty()) {
            return primary.get(0);
        }
        List<String> side = stringList(shot.get("sideCharacters"));
        return side.isEmpty() ? "" : side.get(0);
    }

    private String perspectiveLightingDescription(Map<String, Object> shot, Map<String, Object> key, Map<String, Object> fill, Map<String, Object> rim, Map<String, Object> neg) {
        return roomDescription(shot) + ": " + key.get("householdGearName") + " as key, " + fill.get("householdGearName")
                + " as fill, " + rim.get("householdGearName") + " behind shoulder, " + neg.get("householdGearName")
                + " on shadow side, camera foreground.";
    }

    private String cameraRigName(String budgetTier) {
        if (budgetTier.equals("studio") || budgetTier.equals("mid_budget")) {
            return "Fluid head tripod";
        }
        if (budgetTier.equals("indie")) {
            return "DJI RS3 gimbal";
        }
        return "Phone tripod";
    }

    private double cameraDistance(Map<String, Object> shot) {
        return shotTypeCode(shot.get("shotType")).equals("CU") ? 3.0d : 5.0d;
    }

    private double cameraHeight(Map<String, Object> shot) {
        String angle = stringValue(shot.get("cameraAngle")).toLowerCase(Locale.ROOT);
        if (angle.contains("low")) return 2.7d;
        if (angle.contains("high")) return 6.0d;
        return 4.8d;
    }

    private String cameraAngleHeightPhrase(Map<String, Object> shot) {
        return cameraHeight(shot) + " ft height";
    }

    private String lensCompressionFeel(Object value) {
        String lens = stringValue(value).toLowerCase(Locale.ROOT);
        if (lens.contains("tele") || lens.contains("85") || lens.contains("70")) {
            return "Compressed flat background, subject isolated, shallow depth of field";
        }
        if (lens.contains("wide") || lens.contains("mobile") || lens.contains("24")) {
            return "Exaggerated foreground / background depth, environment dominates";
        }
        return "Natural human-eye depth perception";
    }

    private String itemName(Map<String, Object> placement, boolean household) {
        return stringValue(placement.get(household ? "householdGearName" : "professionalGearName"));
    }

    private String explicitOverride(Map<String, Object> shot) {
        return defaultString(firstString(shot.get("imageGenerationPromptOverride"), shot.get("promptOverride")), "");
    }

    private String firstListValue(Object value, String fallback) {
        List<String> values = stringList(value);
        return values.isEmpty() ? fallback : values.get(0);
    }

    private String soundLayerDescription(Object value, String layerType) {
        for (Map<String, Object> layer : mapList(value)) {
            String type = stringValue(layer.get("layerType"));
            if (layerType.equalsIgnoreCase(type)) {
                return stringValue(layer.get("description"));
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<Map<String, Object>>() {
            });
        }
        return new LinkedHashMap<>();
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {
                }))
                .toList();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
        }
        if (value == null || stringValue(value).isBlank()) {
            return List.of();
        }
        return List.of(stringValue(value));
    }

    private Object nested(Map<String, Object> map, String parent, String child) {
        Object value = map.get(parent);
        if (value instanceof Map<?, ?> nestedMap) {
            return nestedMap.get(child);
        }
        return null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String firstString(Object... values) {
        for (Object value : values) {
            if (value != null && !stringValue(value).isBlank()) {
                return stringValue(value);
            }
        }
        return "";
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null || stringValue(value).isBlank() ? fallback : Integer.parseInt(stringValue(value).replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null || stringValue(value).isBlank() ? fallback : Double.parseDouble(stringValue(value).replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double timeValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = stringValue(value).trim();
        if (text.contains(":")) {
            String[] parts = text.split(":");
            try {
                return (Double.parseDouble(parts[0].replaceAll("[^0-9.\\-]", "")) * 60d)
                        + Double.parseDouble(parts[1].replaceAll("[^0-9.\\-]", ""));
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return doubleValue(text, fallback);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String stringValue(Object value, String fallback) {
        String result = stringValue(value);
        return result.isBlank() ? fallback : result;
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

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return stringValue(value);
        }
    }

    private Map<String, Object> rawTextJsonMap(Object rawText) {
        String text = stripJsonFence(stringValue(rawText).trim());
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String stripJsonFence(String text) {
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int closingFence = text.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                return text.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        return text;
    }

    private record GeneratedTag(Map<String, Object> payload, UUID promptRunId) {
    }
}
