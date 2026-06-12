package com.dalai.llama.creator.service;

import com.dalai.llama.creator.dto.request.ShotTakeStudioPolishRequest;
import com.dalai.llama.creator.domain.GenerationJobStatus;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "creator.ai", name = "studio-polish-kafka-queue-enabled", havingValue = "true", matchIfMissing = true)
public class CreatorStudioPolishKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(CreatorStudioPolishKafkaConsumer.class);
    private static final String JOB_TYPE_STUDIO_POLISH = "SHOT_TAKE_STUDIO_POLISH";
    private static final String JOB_TYPE_STUDIO_POLISH_ALL = "SHOT_TAKE_STUDIO_POLISH_ALL";

    private final CreatorShotTakeService shotTakeService;
    private final GenerationJobService generationJobService;
    private final ObjectMapper objectMapper;

    public CreatorStudioPolishKafkaConsumer(
            CreatorShotTakeService shotTakeService,
            GenerationJobService generationJobService,
            ObjectMapper objectMapper
    ) {
        this.shotTakeService = shotTakeService;
        this.generationJobService = generationJobService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${creator.ai.studio-polish-jobs-topic:creator.studio-polish.jobs}",
            groupId = "${spring.kafka.consumer.group-id:creator-service}",
            concurrency = "${creator.ai.studio-polish-consumer-concurrency:1}"
    )
    public void consume(String rawMessage) {
        Map<String, Object> payload = parsePayload(rawMessage);
        UUID jobId = uuidValue(payload.get("jobId"));
        String jobType = stringValue(payload.get("jobType"), "");
        if (jobId == null) {
            log.error("Studio Polish queued message is missing jobId payload={}", payload);
            return;
        }

        try {
            CreatorGenerationJob existingJob = generationJobService.findGenerationJob(jobId).orElse(null);
            if (existingJob == null) {
                log.error("Studio Polish queued message references missing jobId={} payload={}", jobId, payload);
                return;
            }
            if (isTerminal(existingJob.getStatus())) {
                log.info("Skipping duplicate Studio Polish message jobId={} jobType={} status={}", jobId, jobType, existingJob.getStatus());
                return;
            }
            if (!generationJobService.claimGenerationJobExecution(jobId, "studio-polish-kafka")) {
                log.info("Skipping already claimed Studio Polish message jobId={} jobType={} status={}", jobId, jobType, existingJob.getStatus());
                return;
            }

            Map<String, Object> queueMetadata = objectMap(payload.get("studioPolishQueue"));
            queueMetadata.put("pickedUpAt", java.time.OffsetDateTime.now().toString());
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    8,
                    "Studio Polish worker picked queued job",
                    Map.of("studioPolishQueue", queueMetadata)
            );

            ShotTakeStudioPolishRequest request = requestValue(payload.get("request"));
            String tenantId = stringValue(payload.get("tenantId"), "unknown");
            String userId = stringValue(payload.get("userId"), "anonymous");
            Map<String, Object> input = existingJob.getInputPayload() == null ? Map.of() : existingJob.getInputPayload();
            log.info(
                    "Studio Polish Kafka picked jobId={} jobType={} status={} queuedAt={} topic={} provider={} model={} providerMode={} seed={} takeId={} scriptId={} shotNumber={} idempotencyKey={} editNoteChars={}",
                    jobId,
                    jobType,
                    existingJob.getStatus(),
                    stringValue(payload.get("queuedAt"), ""),
                    stringValue(objectMap(payload.get("studioPolishQueue")).get("topic"), ""),
                    stringValue(input.get("provider"), ""),
                    stringValue(input.get("model"), ""),
                    stringValue(input.get("providerMode"), ""),
                    input.get("seed"),
                    stringValue(firstNonNull(payload.get("takeId"), input.get("takeId")), ""),
                    stringValue(firstNonNull(payload.get("scriptId"), input.get("scriptId")), ""),
                    firstNonNull(payload.get("shotNumber"), input.get("shotNumber")),
                    shortText(stringValue(input.get("idempotencyKey"), ""), 72),
                    stringValue(input.get("editNote"), "").length()
            );
            if (JOB_TYPE_STUDIO_POLISH_ALL.equalsIgnoreCase(jobType)) {
                UUID scriptId = uuidValue(payload.get("scriptId"));
                if (scriptId == null) {
                    throw new IllegalArgumentException("Queued Studio Polish all-shots job is missing scriptId.");
                }
                shotTakeService.runStudioPolishAllJob(jobId, scriptId, request, tenantId, userId);
                return;
            }

            if (JOB_TYPE_STUDIO_POLISH.equalsIgnoreCase(jobType)) {
                UUID takeId = uuidValue(payload.get("takeId"));
                if (takeId == null) {
                    throw new IllegalArgumentException("Queued Studio Polish job is missing takeId.");
                }
                shotTakeService.runStudioPolishJob(jobId, takeId, request, tenantId, userId);
                return;
            }

            throw new IllegalArgumentException("Unsupported Studio Polish job type: " + jobType);
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.error("Studio Polish Kafka worker failed jobId={} jobType={} errorType={} errorMessage={}",
                    jobId, jobType, ex.getClass().getSimpleName(), ex.getMessage(), ex);
        }
    }

    private Map<String, Object> parsePayload(String rawMessage) {
        try {
            return objectMapper.readValue(rawMessage, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Studio Polish queued message is not valid JSON.", ex);
        }
    }

    private ShotTakeStudioPolishRequest requestValue(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> request = objectMap(value);
        if (request.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(request, ShotTakeStudioPolishRequest.class);
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String shortText(String value, int maxLength) {
        String text = defaultString(value, "").replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength)) + "...";
    }

    private boolean isTerminal(String status) {
        return GenerationJobStatus.COMPLETED.name().equalsIgnoreCase(status)
                || GenerationJobStatus.FAILED.name().equalsIgnoreCase(status);
    }
}
