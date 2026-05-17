package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.GenerationJobStatus;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.repository.CreatorGenerationJobRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class GenerationJobService {

    private final CreatorProperties properties;
    private final CreatorGenerationJobRepository generationJobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public GenerationJobService(
            CreatorProperties properties,
            CreatorGenerationJobRepository generationJobRepository,
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.properties = properties;
        this.generationJobRepository = generationJobRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob startGenerationJob(
            String jobType,
            String tenantId,
            String userId,
            UUID projectId,
            Map<String, Object> inputPayload
    ) {
        CreatorGenerationJob job = CreatorGenerationJob.builder()
                .tenantId(defaultString(tenantId, "unknown"))
                .userId(defaultString(userId, "anonymous"))
                .projectId(projectId)
                .jobType(defaultString(jobType, "GENERATION"))
                .status(GenerationJobStatus.RUNNING.name())
                .progress(5)
                .redisKey(null)
                .kafkaTopic(null)
                .inputPayload(copyPayload(inputPayload))
                .outputPayload(new LinkedHashMap<>())
                .startedAt(OffsetDateTime.now())
                .build();
        job = generationJobRepository.save(job);
        job.setRedisKey("creator:generation:jobs:" + job.getId());
        return generationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob completeGenerationJob(UUID jobId, Map<String, Object> outputPayload) {
        CreatorGenerationJob job = generationJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        job.setStatus(GenerationJobStatus.COMPLETED.name());
        job.setProgress(100);
        job.setOutputPayload(copyPayload(outputPayload));
        job.setCompletedAt(OffsetDateTime.now());
        return generationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob failGenerationJob(UUID jobId, String errorMessage) {
        CreatorGenerationJob job = generationJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        job.setStatus(GenerationJobStatus.FAILED.name());
        job.setProgress(100);
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(OffsetDateTime.now());
        return generationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob publishGenerationJob(
            String jobType,
            String tenantId,
            String userId,
            UUID projectId,
            Map<String, Object> payload
    ) {
        CreatorGenerationJob job = CreatorGenerationJob.builder()
                .tenantId(defaultString(tenantId, "unknown"))
                .userId(defaultString(userId, "anonymous"))
                .projectId(projectId)
                .jobType(defaultString(jobType, "GENERATION"))
                .status(GenerationJobStatus.PENDING.name())
                .progress(0)
                .kafkaTopic(properties.getKafka().getGenerationJobsTopic())
                .inputPayload(copyPayload(payload))
                .outputPayload(new LinkedHashMap<>())
                .build();
        job = generationJobRepository.save(job);
        job.setRedisKey("creator:generation:jobs:" + job.getId());
        job = generationJobRepository.save(job);

        Map<String, Object> kafkaPayload = copyPayload(payload);
        kafkaPayload.put("jobId", job.getId().toString());
        kafkaPayload.put("jobType", job.getJobType());
        kafkaTemplate.send(properties.getKafka().getGenerationJobsTopic(), job.getId().toString(), kafkaPayload);
        return job;
    }

    public void publishGenerationJob(String jobId, Map<String, Object> payload) {
        Map<String, Object> enrichedPayload = copyPayload(payload);
        if (jobId != null && !jobId.isBlank()) {
            enrichedPayload.put("requestedJobId", jobId);
        }
        UUID projectId = uuidValue(payload == null ? null : payload.get("projectId"));
        publishGenerationJob(
                stringValue(payload == null ? null : payload.get("jobType"), "GENERATION"),
                stringValue(payload == null ? null : payload.get("tenantId"), "unknown"),
                stringValue(payload == null ? null : payload.get("userId"), "anonymous"),
                projectId,
                enrichedPayload
        );
    }

    private Map<String, Object> copyPayload(Map<String, Object> payload) {
        return payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
