package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.dto.response.ShotProductionPlanTagResponse;
import com.dalai.llama.creator.exception.CreatorAiOutputException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CreatorProductionPlanAsyncService {

    private static final Logger log = LoggerFactory.getLogger(CreatorProductionPlanAsyncService.class);

    private final ProductionPlanTagService productionPlanTagService;
    private final GenerationJobService generationJobService;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    public CreatorProductionPlanAsyncService(
            ProductionPlanTagService productionPlanTagService,
            GenerationJobService generationJobService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor,
            ObjectMapper objectMapper
    ) {
        this.productionPlanTagService = productionPlanTagService;
        this.generationJobService = generationJobService;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    public CreatorGenerationJob startProductionPlanGeneration(
            UUID scriptId,
            String styleKey,
            Integer focusedShotNumber,
            boolean forceRegenerate,
            String videoProvider,
            String videoModel,
            Integer maxClipSeconds,
            String tenantId,
            String userId
    ) {
        ProductionPlanTagService.VideoModelCapability videoModelCapability =
                new ProductionPlanTagService.VideoModelCapability(videoProvider, videoModel, maxClipSeconds);
        ProductionPlanTagService.ProductionPlanJobStart jobStart = productionPlanTagService.startGenerateTagsForScriptJob(
                scriptId,
                styleKey,
                focusedShotNumber,
                forceRegenerate,
                videoModelCapability,
                tenantId,
                userId
        );
        CreatorGenerationJob job = jobStart.job();
        if (!jobStart.shouldRun()) {
            log.info(
                    "Creator async production plan generation reused existing job jobId={} scriptId={} tenantId={} userId={} status={} reason={}",
                    job.getId(),
                    scriptId,
                    tenantId,
                    userId,
                    job.getStatus(),
                    jobStart.reason()
            );
            return job;
        }
        log.info(
                "Creator async production plan generation submitting worker jobId={} scriptId={} tenantId={} userId={} styleKey={} focusedShotNumber={} forceRegenerate={} videoProvider={} videoModel={} maxClipSeconds={}",
                job.getId(),
                scriptId,
                tenantId,
                userId,
                styleKey,
                focusedShotNumber,
                forceRegenerate,
                videoProvider,
                videoModel,
                maxClipSeconds
        );
        try {
            taskExecutor.execute(() -> {
                log.info(
                        "Creator async production plan worker started jobId={} scriptId={} tenantId={} userId={}",
                        job.getId(),
                        scriptId,
                        tenantId,
                        userId
                );
                try {
                    productionPlanTagService.runGenerateTagsForScriptJob(job.getId(), scriptId, styleKey, focusedShotNumber, forceRegenerate, videoModelCapability, tenantId, userId);
                    log.info(
                            "Creator async production plan worker completed jobId={} scriptId={} tenantId={} userId={}",
                            job.getId(),
                            scriptId,
                            tenantId,
                            userId
                    );
                } catch (Throwable ex) {
                    Map<String, Object> debug = ex instanceof CreatorAiOutputException aiOutputException
                            ? new LinkedHashMap<>(aiOutputException.getDebugPayload())
                            : Map.of();
                    generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()), debug);
                    log.error(
                            "Creator async production plan generation failed jobId={} scriptId={} tenantId={} userId={} errorType={} errorMessage={}",
                            job.getId(),
                            scriptId,
                            tenantId,
                            userId,
                            ex.getClass().getSimpleName(),
                            ex.getMessage(),
                            ex
                    );
                }
            });
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.error(
                    "Creator async production plan worker submission failed jobId={} scriptId={} tenantId={} userId={} errorType={} errorMessage={}",
                    job.getId(),
                    scriptId,
                    tenantId,
                    userId,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex
            );
            throw ex;
        }
        return job;
    }

    public List<Map<String, Object>> listProductionPlans(UUID scriptId) {
        return objectMapper.convertValue(
                productionPlanTagService.listTagsForScript(scriptId),
                new TypeReference<List<Map<String, Object>>>() {
                }
        );
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
