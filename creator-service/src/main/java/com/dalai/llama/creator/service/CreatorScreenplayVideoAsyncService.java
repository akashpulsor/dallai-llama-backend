package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CreatorScreenplayVideoAsyncService {

    private static final Logger log = LoggerFactory.getLogger(CreatorScreenplayVideoAsyncService.class);

    private final ScreenplayVideoService screenplayVideoService;
    private final GenerationJobService generationJobService;
    private final TaskExecutor taskExecutor;

    public CreatorScreenplayVideoAsyncService(
            ScreenplayVideoService screenplayVideoService,
            GenerationJobService generationJobService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.screenplayVideoService = screenplayVideoService;
        this.generationJobService = generationJobService;
        this.taskExecutor = taskExecutor;
    }

    public CreatorGenerationJob startVideoGeneration(
            UUID scriptId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        long requestStartedNanos = System.nanoTime();
        CreatorGenerationJob job = screenplayVideoService.startVideoGenerationJob(scriptId, request, tenantId, userId);
        UUID runId = uuidValue(job.getOutputPayload() == null ? null : job.getOutputPayload().get("runId"));
        if (runId == null) {
            generationJobService.failGenerationJob(job.getId(), "Video run id was not created for the scene queue.");
            throw new IllegalStateException("Video run id was not created for the scene queue.");
        }
        boolean prepareOnly = booleanValue(request == null ? null : request.get("prepareOnly"), false)
                || "scene_by_scene".equalsIgnoreCase(defaultString(
                        request == null ? null : String.valueOf(request.get("generationWorkflow")),
                        ""
                ));
        if (prepareOnly) {
            Map<String, Object> output = new LinkedHashMap<>(
                    job.getOutputPayload() == null ? Map.of() : job.getOutputPayload()
            );
            output.put("runId", runId.toString());
            output.put("prepareOnly", true);
            output.put("generationWorkflow", "scene_by_scene");
            output.put("message", "Scene workspace ready. Generate and review one scene at a time.");
            CreatorGenerationJob completed = generationJobService.completeGenerationJob(job.getId(), output);
            log.info(
                    "Screenplay scene workspace prepared without provider dispatch jobId={} scriptId={} runId={} tenantId={} userId={} responseMs={}",
                    job.getId(),
                    scriptId,
                    runId,
                    tenantId,
                    userId,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedNanos)
            );
            return completed;
        }
        log.info("Screenplay video queue accepted jobId={} scriptId={} runId={} tenantId={} userId={} responseMs={}",
                job.getId(),
                scriptId,
                runId,
                tenantId,
                userId,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedNanos));
        taskExecutor.execute(() -> {
            long workerStartedNanos = System.nanoTime();
            log.info("Screenplay video queue worker dispatched jobId={} scriptId={} runId={}", job.getId(), scriptId, runId);
            try {
                screenplayVideoService.runVideoGenerationJob(job.getId(), scriptId, runId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error(
                        "Screenplay video queue worker crashed jobId={} runId={} tenantId={} userId={} errorType={} errorMessage={}",
                        job.getId(),
                        runId,
                        tenantId,
                        userId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage(),
                        ex
                );
            } finally {
                log.info("Screenplay video queue worker finished jobId={} scriptId={} runId={} elapsedMs={}",
                        job.getId(),
                        scriptId,
                        runId,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - workerStartedNanos));
            }
        });
        return job;
    }

    public CreatorGenerationJob startSceneRegeneration(
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        CreatorGenerationJob job = screenplayVideoService.startRegenerateSceneJob(runId, sceneId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                screenplayVideoService.runRegenerateSceneJob(job.getId(), runId, sceneId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error(
                        "Screenplay video scene worker crashed jobId={} runId={} sceneId={} tenantId={} userId={} errorType={} errorMessage={}",
                        job.getId(),
                        runId,
                        sceneId,
                        tenantId,
                        userId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage(),
                        ex
                );
            }
        });
        return job;
    }

    public CreatorGenerationJob startFinalRender(
            UUID runId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        CreatorGenerationJob job = screenplayVideoService.startFinalRenderJob(runId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                screenplayVideoService.runFinalRenderJob(job.getId(), runId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error(
                        "Screenplay final render worker crashed jobId={} runId={} tenantId={} userId={} errorType={} errorMessage={}",
                        job.getId(),
                        runId,
                        tenantId,
                        userId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage(),
                        ex
                );
            }
        });
        return job;
    }

    public CreatorGenerationJob startAudioPack(
            UUID runId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        CreatorGenerationJob job = screenplayVideoService.startAudioPackJob(runId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                screenplayVideoService.runAudioPackJob(job.getId(), runId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error(
                        "Screenplay audio pack worker crashed jobId={} runId={} tenantId={} userId={} errorType={} errorMessage={}",
                        job.getId(),
                        runId,
                        tenantId,
                        userId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage(),
                        ex
                );
            }
        });
        return job;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(normalized);
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

}
