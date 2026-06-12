package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.dto.request.EnhanceAllShotTakesRequest;
import com.dalai.llama.creator.dto.request.ShotTakeAudioEnhanceRequest;
import com.dalai.llama.creator.dto.request.ShotTakeAudioMixRequest;
import com.dalai.llama.creator.dto.request.ShotTakeEnhancePreviewRequest;
import com.dalai.llama.creator.dto.request.ShotTakeFinalRenderRequest;
import com.dalai.llama.creator.dto.request.ShotTakeSoundGenerateRequest;
import com.dalai.llama.creator.dto.request.ShotTakeStudioPolishRequest;
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
public class CreatorShotTakeAsyncService {

    private static final Logger log = LoggerFactory.getLogger(CreatorShotTakeAsyncService.class);
    private static final String JOB_TYPE_STUDIO_POLISH = "SHOT_TAKE_STUDIO_POLISH";
    private static final String JOB_TYPE_STUDIO_POLISH_ALL = "SHOT_TAKE_STUDIO_POLISH_ALL";

    private final CreatorShotTakeService shotTakeService;
    private final GenerationJobService generationJobService;
    private final TaskExecutor taskExecutor;
    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;

    public CreatorShotTakeAsyncService(
            CreatorShotTakeService shotTakeService,
            GenerationJobService generationJobService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor,
            CreatorProperties properties,
            ObjectMapper objectMapper
    ) {
        this.shotTakeService = shotTakeService;
        this.generationJobService = generationJobService;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public CreatorGenerationJob startReview(UUID takeId, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startReviewJob(takeId, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-review")) {
                    return;
                }
                shotTakeService.runReviewJob(job.getId(), takeId, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take review worker crashed jobId={} takeId={} errorType={} errorMessage={}",
                        job.getId(), takeId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startEnhancementPreview(UUID takeId, ShotTakeEnhancePreviewRequest request, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startEnhancementPreviewJob(takeId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-enhancement-preview")) {
                    return;
                }
                shotTakeService.runEnhancementPreviewJob(job.getId(), takeId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take enhancement preview worker crashed jobId={} takeId={} errorType={} errorMessage={}",
                        job.getId(), takeId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startEnhanceAll(UUID scriptId, EnhanceAllShotTakesRequest request, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startEnhanceAllJob(scriptId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-enhance-all")) {
                    return;
                }
                shotTakeService.runEnhanceAllJob(job.getId(), scriptId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take enhance-all worker crashed jobId={} scriptId={} errorType={} errorMessage={}",
                        job.getId(), scriptId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startStudioPolish(UUID takeId, ShotTakeStudioPolishRequest request, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startStudioPolishJob(takeId, request, tenantId, userId);
        if (properties.getAi().isStudioPolishKafkaQueueEnabled()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("takeId", takeId.toString());
            payload.put("tenantId", tenantId);
            payload.put("userId", userId);
            payload.put("request", requestMap(request));
            return enqueueStudioPolishJob(job, JOB_TYPE_STUDIO_POLISH, payload);
        }
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-studio-polish")) {
                    return;
                }
                shotTakeService.runStudioPolishJob(job.getId(), takeId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take studio-polish worker crashed jobId={} takeId={} errorType={} errorMessage={}",
                        job.getId(), takeId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startAudioEnhancement(UUID takeId, ShotTakeAudioEnhanceRequest request, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startAudioEnhancementJob(takeId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-audio-enhancement")) {
                    return;
                }
                shotTakeService.runAudioEnhancementJob(job.getId(), takeId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take audio-enhancement worker crashed jobId={} takeId={} errorType={} errorMessage={}",
                        job.getId(), takeId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startAudioMix(UUID takeId, ShotTakeAudioMixRequest request, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startAudioMixJob(takeId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-audio-mix")) {
                    return;
                }
                shotTakeService.runAudioMixJob(job.getId(), takeId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take audio-mix worker crashed jobId={} takeId={} errorType={} errorMessage={}",
                        job.getId(), takeId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startSoundGeneration(UUID takeId, ShotTakeSoundGenerateRequest request, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startSoundGenerationJob(takeId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-sound-generation")) {
                    return;
                }
                shotTakeService.runSoundGenerationJob(job.getId(), takeId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take sound-generation worker crashed jobId={} takeId={} errorType={} errorMessage={}",
                        job.getId(), takeId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startFinalRender(UUID takeId, ShotTakeFinalRenderRequest request, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startFinalRenderJob(takeId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-final-render")) {
                    return;
                }
                shotTakeService.runFinalRenderJob(job.getId(), takeId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take final-render worker crashed jobId={} takeId={} errorType={} errorMessage={}",
                        job.getId(), takeId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startAcceptedSequenceRender(UUID scriptId, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startAcceptedSequenceRenderJob(scriptId, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-accepted-sequence-render")) {
                    return;
                }
                shotTakeService.runAcceptedSequenceRenderJob(job.getId(), scriptId, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Accepted shot sequence render worker crashed jobId={} scriptId={} errorType={} errorMessage={}",
                        job.getId(), scriptId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    public CreatorGenerationJob startStudioPolishAll(UUID scriptId, ShotTakeStudioPolishRequest request, String tenantId, String userId) {
        CreatorGenerationJob job = shotTakeService.startStudioPolishAllJob(scriptId, request, tenantId, userId);
        if (properties.getAi().isStudioPolishKafkaQueueEnabled()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scriptId", scriptId.toString());
            payload.put("tenantId", tenantId);
            payload.put("userId", userId);
            payload.put("request", requestMap(request));
            return enqueueStudioPolishJob(job, JOB_TYPE_STUDIO_POLISH_ALL, payload);
        }
        taskExecutor.execute(() -> {
            try {
                if (!claimJob(job.getId(), "shot-take-studio-polish-all")) {
                    return;
                }
                shotTakeService.runStudioPolishAllJob(job.getId(), scriptId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                generationJobService.failGenerationJob(job.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
                log.error("Shot take studio-polish-all worker crashed jobId={} scriptId={} errorType={} errorMessage={}",
                        job.getId(), scriptId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        });
        return job;
    }

    private boolean claimJob(UUID jobId, String workerName) {
        boolean claimed = generationJobService.claimGenerationJobExecution(jobId, workerName);
        if (!claimed) {
            log.info("Skipping duplicate creator async worker jobId={} worker={}", jobId, workerName);
        }
        return claimed;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private CreatorGenerationJob enqueueStudioPolishJob(
            CreatorGenerationJob job,
            String jobType,
            Map<String, Object> payload
    ) {
        String topic = properties.getAi().getStudioPolishJobsTopic();
        Map<String, Object> queueMetadata = new LinkedHashMap<>();
        queueMetadata.put("topic", topic);
        queueMetadata.put("jobType", jobType);
        queueMetadata.put("consumerConcurrency", properties.getAi().getStudioPolishConsumerConcurrency());

        CreatorGenerationJob queuedJob = generationJobService.queueExistingGenerationJob(
                job.getId(),
                topic,
                "Queued for Studio Polish worker",
                Map.of("studioPolishQueue", queueMetadata)
        );

        Map<String, Object> kafkaPayload = new LinkedHashMap<>(payload);
        kafkaPayload.put("jobType", jobType);
        kafkaPayload.put("queuedAt", java.time.OffsetDateTime.now().toString());
        kafkaPayload.put("studioPolishQueue", queueMetadata);
        Map<String, Object> input = job.getInputPayload() == null ? Map.of() : job.getInputPayload();
        log.info(
                "Studio Polish queued jobId={} jobType={} topic={} provider={} model={} providerMode={} seed={} takeId={} scriptId={} shotNumber={} idempotencyKey={} editNoteChars={} consumerConcurrency={}",
                job.getId(),
                jobType,
                topic,
                stringValue(input.get("provider"), ""),
                stringValue(input.get("model"), ""),
                stringValue(input.get("providerMode"), ""),
                input.get("seed"),
                stringValue(input.get("takeId"), ""),
                stringValue(input.get("scriptId"), ""),
                input.get("shotNumber"),
                shortText(stringValue(input.get("idempotencyKey"), ""), 72),
                stringValue(input.get("editNote"), "").length(),
                properties.getAi().getStudioPolishConsumerConcurrency()
        );
        generationJobService.publishExistingGenerationJob(topic, job.getId(), kafkaPayload);
        return queuedJob;
    }

    private Map<String, Object> requestMap(ShotTakeStudioPolishRequest request) {
        if (request == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(request, new TypeReference<>() {
        });
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String shortText(String value, int maxLength) {
        String text = defaultString(value, "").replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength)) + "...";
    }
}


