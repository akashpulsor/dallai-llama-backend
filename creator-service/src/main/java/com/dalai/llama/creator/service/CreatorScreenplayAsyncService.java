package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.dto.request.GenerateStoryIdeaScriptRequest;
import com.dalai.llama.creator.dto.response.GeneratedScriptResponse;
import com.dalai.llama.creator.exception.CreatorAiOutputException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CreatorScreenplayAsyncService {

    private static final Logger log = LoggerFactory.getLogger(CreatorScreenplayAsyncService.class);

    private final IdeaService ideaService;
    private final GenerationJobService generationJobService;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    public CreatorScreenplayAsyncService(
            IdeaService ideaService,
            GenerationJobService generationJobService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor,
            ObjectMapper objectMapper
    ) {
        this.ideaService = ideaService;
        this.generationJobService = generationJobService;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    public CreatorGenerationJob startScreenplayGeneration(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            GenerateStoryIdeaScriptRequest request,
            String tenantId,
            String userId
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("lockedIdeaId", lockedIdeaId == null ? null : lockedIdeaId.toString());
        input.put("storyIdeaId", storyIdeaId == null ? null : storyIdeaId.toString());
        input.put("durationSeconds", request == null ? null : request.durationSeconds());
        input.put("categoryCode", request == null ? null : request.categoryCode());
        input.put("dialogueLanguage", request == null ? null : request.dialogueLanguage());
        input.put("screenType", request == null ? null : request.screenType());
        input.put("storytellingType", request == null ? null : request.storytellingType());
        input.put("hookLens", request == null ? null : request.hookLens());
        input.put("budgetTier", request == null ? null : request.budgetTier());
        input.put("productionStyle", request == null ? null : request.productionStyle());
        input.put("hybridSceneMode", request == null ? null : request.hybridSceneMode());
        input.put("brollStyle", request == null ? null : request.brollStyle());
        input.put("captionStyle", request == null ? null : request.captionStyle());
        input.put("productionStyleGuidance", request == null ? null : request.productionStyleGuidance());
        input.put("screenplayVideoGenerationPackage", request == null ? null : request.screenplayVideoGenerationPackage());
        input.put("idempotencyKey", screenplayIdempotencyKey(lockedIdeaId, storyIdeaId, request));

        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        String idempotencyKey = String.valueOf(input.get("idempotencyKey"));
        CreatorGenerationJob activeJob = generationJobService
                .findActiveGenerationJobByIdempotencyKey(safeTenantId, safeUserId, PromptTemplateType.SCRIPT_GENERATE.name(), idempotencyKey)
                .orElse(null);
        if (activeJob != null) {
            log.info(
                    "Creator async screenplay generation reused active job jobId={} lockedIdeaId={} storyIdeaId={} tenantId={} userId={} idempotencyKey={}",
                    activeJob.getId(),
                    lockedIdeaId,
                    storyIdeaId,
                    safeTenantId,
                    safeUserId,
                    shortText(idempotencyKey, 72)
            );
            return activeJob;
        }

        CreatorGenerationJob job;
        try {
            job = generationJobService.startGenerationJob(
                    PromptTemplateType.SCRIPT_GENERATE.name(),
                    safeTenantId,
                    safeUserId,
                    null,
                    input
            );
        } catch (DataIntegrityViolationException ex) {
            CreatorGenerationJob recoveredJob = generationJobService
                    .findActiveGenerationJobByIdempotencyKey(safeTenantId, safeUserId, PromptTemplateType.SCRIPT_GENERATE.name(), idempotencyKey)
                    .orElse(null);
            if (recoveredJob != null) {
                log.info(
                        "Creator async screenplay generation duplicate recovered jobId={} lockedIdeaId={} storyIdeaId={} tenantId={} userId={} idempotencyKey={}",
                        recoveredJob.getId(),
                        lockedIdeaId,
                        storyIdeaId,
                        safeTenantId,
                        safeUserId,
                        shortText(idempotencyKey, 72)
                );
                return recoveredJob;
            }
            throw ex;
        }

        taskExecutor.execute(() -> runScreenplayGeneration(job.getId(), lockedIdeaId, storyIdeaId, request, tenantId, userId));
        return job;
    }

    private void runScreenplayGeneration(
            UUID jobId,
            UUID lockedIdeaId,
            UUID storyIdeaId,
            GenerateStoryIdeaScriptRequest request,
            String tenantId,
            String userId
    ) {
        if (!generationJobService.claimGenerationJobExecution(jobId, "screenplay-generation")) {
            log.info(
                    "Skipping duplicate screenplay generation execution jobId={} lockedIdeaId={} storyIdeaId={} tenantId={} userId={}",
                    jobId,
                    lockedIdeaId,
                    storyIdeaId,
                    tenantId,
                    userId
            );
            return;
        }
        try {
            generationJobService.updateGenerationJobProgress(jobId, 12, "Generating shot-wise screenplay JSON");
            GeneratedScriptResponse response = ideaService.generateScriptForStoryIdeaForJob(lockedIdeaId, storyIdeaId, request, tenantId, userId, jobId);
            Map<String, Object> output = objectMapper.convertValue(response, new TypeReference<Map<String, Object>>() {
            });
            output.put("message", "Screenplay JSON generated. Generate shot plan JSON next.");
            generationJobService.completeGenerationJob(jobId, output);
        } catch (RuntimeException ex) {
            Map<String, Object> debug = ex instanceof CreatorAiOutputException aiOutputException
                    ? new LinkedHashMap<>(aiOutputException.getDebugPayload())
                    : Map.of();
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()), debug);
            log.error(
                    "Creator async screenplay generation failed jobId={} lockedIdeaId={} storyIdeaId={} tenantId={} userId={} errorType={} errorMessage={}",
                    jobId,
                    lockedIdeaId,
                    storyIdeaId,
                    tenantId,
                    userId,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex
            );
        }
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String screenplayIdempotencyKey(UUID lockedIdeaId, UUID storyIdeaId, GenerateStoryIdeaScriptRequest request) {
        Map<String, Object> stableInput = new LinkedHashMap<>();
        stableInput.put("lockedIdeaId", lockedIdeaId == null ? null : lockedIdeaId.toString());
        stableInput.put("storyIdeaId", storyIdeaId == null ? null : storyIdeaId.toString());
        stableInput.put("durationSeconds", request == null ? null : request.durationSeconds());
        stableInput.put("categoryCode", request == null ? null : request.categoryCode());
        stableInput.put("idea", request == null ? null : request.idea());
        stableInput.put("dialogueLanguage", request == null ? null : request.dialogueLanguage());
        stableInput.put("screenType", request == null ? null : request.screenType());
        stableInput.put("storytellingType", request == null ? null : request.storytellingType());
        stableInput.put("hookLens", request == null ? null : request.hookLens());
        stableInput.put("budgetTier", request == null ? null : request.budgetTier());
        stableInput.put("productionStyle", request == null ? null : request.productionStyle());
        stableInput.put("hybridSceneMode", request == null ? null : request.hybridSceneMode());
        stableInput.put("brollStyle", request == null ? null : request.brollStyle());
        stableInput.put("captionStyle", request == null ? null : request.captionStyle());
        stableInput.put("productionStyleGuidance", request == null ? null : request.productionStyleGuidance());
        stableInput.put("screenplayVideoGenerationPackage", request == null ? null : request.screenplayVideoGenerationPackage());
        stableInput.put("characterCastMappings", request == null ? null : request.characterCastMappings());
        stableInput.put("availableActors", request == null ? null : request.availableActors());
        stableInput.put("audienceDecision", request == null ? null : request.audienceDecision());
        stableInput.put("brandContext", request == null ? null : request.brandContext());
        stableInput.put("creatorContext", request == null ? null : request.creatorContext());
        stableInput.put("workflowStorytellingType", request == null || request.context() == null ? null : request.context().storytellingType());
        stableInput.put("workflowHookLens", request == null || request.context() == null ? null : request.context().hookLens());
        stableInput.put("lockedPackage", request == null || request.context() == null ? null : request.context().lockedPackage());
        return "screenplay:" + sha256Hex(writeJson(stableInput));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
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

    private String shortText(String value, int maxLength) {
        String text = defaultString(value, "");
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength));
    }
}
