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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

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
        input.put("budgetTier", request == null ? null : request.budgetTier());

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                PromptTemplateType.SCRIPT_GENERATE.name(),
                defaultString(tenantId, "unknown"),
                defaultString(userId, "anonymous"),
                null,
                input
        );

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
        try {
            generationJobService.updateGenerationJobProgress(jobId, 12, "Generating shot-wise screenplay JSON");
            GeneratedScriptResponse response = ideaService.generateScriptForStoryIdea(lockedIdeaId, storyIdeaId, request, tenantId, userId);
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
}
