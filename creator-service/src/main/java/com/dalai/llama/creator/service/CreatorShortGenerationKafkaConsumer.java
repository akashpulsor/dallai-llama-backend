package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.GenerationJobStatus;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.dto.request.GenerateShortsRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "creator.ai", name = "shorts-generation-kafka-queue-enabled", havingValue = "true", matchIfMissing = true)
public class CreatorShortGenerationKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(CreatorShortGenerationKafkaConsumer.class);
    private static final String JOB_TYPE = "SHORTS_GENERATE";
    private static final String JOB_TYPE_VISUAL_ANALYSIS = "SHORTS_VISUAL_ANALYSIS";
    private static final String JOB_TYPE_RERENDER = "SHORTS_CANDIDATE_RERENDER";

    private final CreatorShortGenerationService shortGenerationService;
    private final GenerationJobService generationJobService;
    private final ObjectMapper objectMapper;

    public CreatorShortGenerationKafkaConsumer(
            CreatorShortGenerationService shortGenerationService,
            GenerationJobService generationJobService,
            ObjectMapper objectMapper
    ) {
        this.shortGenerationService = shortGenerationService;
        this.generationJobService = generationJobService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${creator.kafka.generation-jobs-topic:creator.generation.jobs}",
            groupId = "${spring.kafka.consumer.group-id:creator-service}",
            concurrency = "${creator.ai.shorts-generation-consumer-concurrency:1}"
    )
    public void consume(String rawMessage) {
        Map<String, Object> payload = parsePayload(rawMessage);
        String jobType = stringValue(payload.get("jobType"), "");
        if (!JOB_TYPE.equalsIgnoreCase(jobType) && !JOB_TYPE_RERENDER.equalsIgnoreCase(jobType) && !JOB_TYPE_VISUAL_ANALYSIS.equalsIgnoreCase(jobType)) {
            return;
        }

        UUID jobId = uuidValue(payload.get("jobId"));
        UUID videoId = uuidValue(payload.get("shortVideoId"));
        UUID sourceAssetId = uuidValue(payload.get("sourceAssetId"));
        UUID candidateId = uuidValue(payload.get("candidateId"));
        if (JOB_TYPE_RERENDER.equalsIgnoreCase(jobType)) {
            consumeRerender(jobId, videoId, candidateId, payload);
            return;
        }
        if (JOB_TYPE_VISUAL_ANALYSIS.equalsIgnoreCase(jobType)) {
            consumeVisualAnalysis(jobId, videoId, sourceAssetId, payload);
            return;
        }
        if (jobId == null || videoId == null || sourceAssetId == null) {
            log.error("Generate Shorts queued message is missing ids payload={}", payload);
            return;
        }

        try {
            CreatorGenerationJob existingJob = generationJobService.findGenerationJob(jobId).orElse(null);
            if (existingJob == null) {
                log.error("Generate Shorts queued message references missing jobId={} payload={}", jobId, payload);
                return;
            }
            if (isTerminal(existingJob.getStatus())) {
                log.info("Skipping duplicate Generate Shorts message jobId={} status={}", jobId, existingJob.getStatus());
                return;
            }
            if (GenerationJobStatus.PAUSED.name().equalsIgnoreCase(existingJob.getStatus())) {
                log.info("Skipping paused Generate Shorts message jobId={} status={}", jobId, existingJob.getStatus());
                return;
            }
            if (!generationJobService.claimGenerationJobExecution(jobId, "generate-shorts-kafka")) {
                log.info("Skipping already claimed Generate Shorts message jobId={} status={}", jobId, existingJob.getStatus());
                return;
            }

            Map<String, Object> queueMetadata = objectMap(payload.get("shortsQueue"));
            queueMetadata.put("pickedUpAt", OffsetDateTime.now().toString());
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    8,
                    "Generate Shorts worker picked queued job",
                    Map.of("shortsQueue", queueMetadata)
            );

            GenerateShortsRequest request = requestValue(payload.get("request"));
            log.info(
                    "Generate Shorts Kafka picked jobId={} videoId={} sourceAssetId={} queuedAt={} requestedShorts={} targetDuration={} platform={}",
                    jobId,
                    videoId,
                    sourceAssetId,
                    stringValue(payload.get("queuedAt"), ""),
                    request == null ? null : request.requestedShorts(),
                    request == null ? null : request.targetDurationSeconds(),
                    request == null ? null : request.platform()
            );
            shortGenerationService.runQueuedGenerationJob(jobId, videoId, sourceAssetId, request);
        } catch (RuntimeException ex) {
            if (isTransientDatabaseFailure(ex)) {
                log.warn(
                        "Generate Shorts Kafka worker encountered transient database failure jobId={} videoId={} errorType={} errorMessage={}; rethrowing so the message is retried instead of marking the job failed.",
                        jobId,
                        videoId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
                throw ex;
            }
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.error("Generate Shorts Kafka worker failed jobId={} videoId={} errorType={} errorMessage={}",
                    jobId, videoId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
        }
    }

    private void consumeRerender(UUID jobId, UUID videoId, UUID candidateId, Map<String, Object> payload) {
        if (jobId == null || videoId == null || candidateId == null) {
            log.error("Generate Shorts rerender queued message is missing ids payload={}", payload);
            return;
        }
        try {
            CreatorGenerationJob existingJob = generationJobService.findGenerationJob(jobId).orElse(null);
            if (existingJob == null) {
                log.error("Generate Shorts rerender message references missing jobId={} payload={}", jobId, payload);
                return;
            }
            if (isTerminal(existingJob.getStatus())) {
                log.info("Skipping duplicate Generate Shorts rerender message jobId={} status={}", jobId, existingJob.getStatus());
                return;
            }
            if (!generationJobService.claimGenerationJobExecution(jobId, "generate-shorts-rerender-kafka")) {
                log.info("Skipping already claimed Generate Shorts rerender message jobId={} status={}", jobId, existingJob.getStatus());
                return;
            }
            Map<String, Object> queueMetadata = objectMap(payload.get("shortsQueue"));
            queueMetadata.put("pickedUpAt", OffsetDateTime.now().toString());
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    8,
                    "Generate Shorts rerender worker picked queued job",
                    Map.of("shortsQueue", queueMetadata)
            );
            log.info("Generate Shorts rerender Kafka picked jobId={} videoId={} candidateId={}", jobId, videoId, candidateId);
            shortGenerationService.runQueuedCandidateRerenderJob(jobId, videoId, candidateId);
        } catch (RuntimeException ex) {
            if (isTransientDatabaseFailure(ex)) {
                log.warn(
                        "Generate Shorts rerender worker encountered transient database failure jobId={} videoId={} candidateId={} errorType={} errorMessage={}; rethrowing so the message is retried instead of marking the job failed.",
                        jobId,
                        videoId,
                        candidateId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
                throw ex;
            }
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.error("Generate Shorts rerender Kafka worker failed jobId={} videoId={} candidateId={} errorType={} errorMessage={}",
                    jobId, videoId, candidateId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
        }
    }

    private void consumeVisualAnalysis(UUID jobId, UUID videoId, UUID sourceAssetId, Map<String, Object> payload) {
        if (jobId == null || videoId == null || sourceAssetId == null) {
            log.error("Optional visual analysis queued message is missing ids payload={}", payload);
            return;
        }
        try {
            CreatorGenerationJob existingJob = generationJobService.findGenerationJob(jobId).orElse(null);
            if (existingJob == null) {
                log.error("Optional visual analysis message references missing jobId={} payload={}", jobId, payload);
                return;
            }
            if (isTerminal(existingJob.getStatus())) {
                log.info("Skipping duplicate optional visual analysis message jobId={} status={}", jobId, existingJob.getStatus());
                return;
            }
            if (!generationJobService.claimGenerationJobExecution(jobId, "shorts-visual-analysis-kafka")) {
                log.info("Skipping already claimed optional visual analysis message jobId={} status={}", jobId, existingJob.getStatus());
                return;
            }
            Map<String, Object> queueMetadata = objectMap(payload.get("visualAnalysis"));
            queueMetadata.put("pickedUpAt", OffsetDateTime.now().toString());
            generationJobService.updateGenerationJobProgress(
                    jobId,
                    8,
                    "Optional visual analysis worker picked queued job",
                    Map.of("visualAnalysis", queueMetadata)
            );
            log.info("Optional visual analysis Kafka picked jobId={} videoId={} sourceAssetId={}", jobId, videoId, sourceAssetId);
            shortGenerationService.runQueuedVisualAnalysisJob(jobId, videoId, sourceAssetId);
        } catch (RuntimeException ex) {
            if (isTransientDatabaseFailure(ex)) {
                log.warn(
                        "Optional visual analysis worker encountered transient database failure jobId={} videoId={} errorType={} errorMessage={}; rethrowing so the message is retried instead of marking the job failed.",
                        jobId,
                        videoId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
                throw ex;
            }
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.error("Optional visual analysis worker failed jobId={} videoId={} errorType={} errorMessage={}",
                    jobId, videoId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
        }
    }

    private Map<String, Object> parsePayload(String rawMessage) {
        try {
            return objectMapper.readValue(rawMessage, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Generate Shorts queued message is not valid JSON.", ex);
        }
    }

    private GenerateShortsRequest requestValue(Object value) {
        Map<String, Object> request = objectMap(value);
        if (request.isEmpty()) {
            return new GenerateShortsRequest(null, null, null, null, null, null, null, null, null);
        }
        return objectMapper.convertValue(request, GenerateShortsRequest.class);
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

    private boolean isTerminal(String status) {
        return GenerationJobStatus.COMPLETED.name().equalsIgnoreCase(status)
                || GenerationJobStatus.FAILED.name().equalsIgnoreCase(status)
                || GenerationJobStatus.CANCELLED.name().equalsIgnoreCase(status);
    }

    private boolean isTransientDatabaseFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof DataAccessResourceFailureException
                    || cursor instanceof CannotCreateTransactionException
                    || cursor instanceof JDBCConnectionException) {
                return true;
            }
            if (cursor instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
