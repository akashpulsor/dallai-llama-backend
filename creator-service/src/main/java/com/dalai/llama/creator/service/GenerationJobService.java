package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.GenerationJobStatus;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.repository.CreatorGenerationJobRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class GenerationJobService {

    private static final Set<String> SHORTS_COMPACT_RUNNING_JOB_TYPES = Set.of(
            "SHORTS_GENERATE",
            "SHORTS_VISUAL_ANALYSIS"
    );
    private static final Set<String> SHORTS_RUNNING_HEAVY_OUTPUT_KEYS = Set.of(
            "transcript",
            "transcriptGraph",
            "graph",
            "sceneTimeline",
            "sceneAnalysis",
            "scenes",
            "frames",
            "candidatePayloads",
            "candidates",
            "storyBeatPlans",
            "storyBeatIntents",
            "visualStoryPlans",
            "compressionPlans",
            "hooks"
    );

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
    public CreatorGenerationJob queueExistingGenerationJob(
            UUID jobId,
            String kafkaTopic,
            String message,
            Map<String, Object> outputPatch
    ) {
        CreatorGenerationJob job = generationJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        job.setStatus(GenerationJobStatus.PENDING.name());
        job.setProgress(0);
        job.setKafkaTopic(defaultString(kafkaTopic, properties.getKafka().getGenerationJobsTopic()));
        job.setStartedAt(null);
        Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
        if (message != null && !message.isBlank()) {
            outputPayload.put("message", message);
        }
        if (outputPatch != null && !outputPatch.isEmpty()) {
            outputPayload.putAll(outputPatch);
        }
        job.setOutputPayload(outputPayload);
        return generationJobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public Optional<CreatorGenerationJob> findGenerationJob(UUID jobId) {
        return generationJobRepository.findById(jobId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGenerationJobCheckpoint(UUID jobId, String checkpointKey) {
        if (jobId == null || checkpointKey == null || checkpointKey.isBlank()) {
            return new LinkedHashMap<>();
        }
        return generationJobRepository.findById(jobId)
                .map(CreatorGenerationJob::getOutputPayload)
                .map(payload -> mapValue(payload == null ? null : payload.get(checkpointKey)))
                .orElseGet(LinkedHashMap::new);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateGenerationJobCheckpoint(UUID jobId, String checkpointKey, Map<String, Object> checkpoint) {
        if (jobId == null || checkpointKey == null || checkpointKey.isBlank() || checkpoint == null || checkpoint.isEmpty()) {
            return;
        }
        generationJobRepository.findById(jobId).ifPresent(job -> {
            if (isTerminalStatus(job.getStatus()) || GenerationJobStatus.PAUSED.name().equalsIgnoreCase(job.getStatus())) {
                return;
            }
            Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
            outputPayload.put(checkpointKey, copyPayload(checkpoint));
            String message = stringValue(checkpoint.get("message"), "");
            if (!message.isBlank()) {
                outputPayload.put("message", message);
            }
            String activeStage = stringValue(checkpoint.get("activeStage"), "");
            if (!activeStage.isBlank()) {
                outputPayload.put("activeStage", activeStage);
            }
            int checkpointProgress = intValue(checkpoint.get("jobProgress"), -1);
            if (checkpointProgress >= 0) {
                job.setProgress(Math.max(
                        Math.max(0, job.getProgress() == null ? 0 : job.getProgress()),
                        Math.min(99, checkpointProgress)
                ));
            }
            outputPayload.put("lastHeartbeatAt", OffsetDateTime.now().toString());
            job.setOutputPayload(outputPayload);
            generationJobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimGenerationJobExecution(UUID jobId, String claimedBy) {
        if (jobId == null) {
            return false;
        }
        return generationJobRepository.claimExecution(
                jobId,
                defaultString(claimedBy, "creator-worker")
        ) == 1;
    }

    @Transactional(readOnly = true)
    public List<CreatorGenerationJob> findStaleClaimedShortGenerationJobs(OffsetDateTime staleBefore, int limit) {
        return generationJobRepository.findStaleClaimedShortGenerationJobs(
                staleBefore == null ? OffsetDateTime.now().minusMinutes(45) : staleBefore,
                Math.max(1, Math.min(limit, 50))
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean clearStaleShortGenerationClaimForRetry(UUID jobId, OffsetDateTime staleBefore, String reason) {
        if (jobId == null) {
            return false;
        }
        return generationJobRepository.clearStaleShortGenerationClaimForRetry(
                jobId,
                staleBefore == null ? OffsetDateTime.now().minusMinutes(45) : staleBefore,
                defaultString(reason, "stale claimed Generate Shorts job recovery"),
                "Recovering stalled Generate Shorts job"
        ) == 1;
    }

    @Transactional(readOnly = true)
    public boolean isPauseRequested(UUID jobId) {
        if (jobId == null) {
            return false;
        }
        return generationJobRepository.findById(jobId)
                .map(CreatorGenerationJob::getOutputPayload)
                .map(payload -> booleanValue(payload == null ? null : payload.get("pauseRequested"), false))
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob requestPauseGenerationJob(UUID jobId, String tenantId, String userId, String reason) {
        CreatorGenerationJob job = generationJobRepository
                .findByIdAndTenantIdAndUserId(
                        jobId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        if (isTerminalStatus(job.getStatus()) || GenerationJobStatus.PAUSED.name().equalsIgnoreCase(job.getStatus())) {
            return job;
        }

        Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
        String safeReason = defaultString(reason, "user_requested_pause");
        outputPayload.put("pauseRequested", true);
        outputPayload.put("pauseRequestedAt", OffsetDateTime.now().toString());
        outputPayload.put("pauseReason", safeReason);
        outputPayload.put("message", "Pause requested. The worker will stop at the next safe stage.");

        if (GenerationJobStatus.PENDING.name().equalsIgnoreCase(job.getStatus())) {
            job.setStatus(GenerationJobStatus.PAUSED.name());
            clearExecutionClaim(outputPayload);
            outputPayload.put("pauseRequested", false);
            outputPayload.put("pausedAt", OffsetDateTime.now().toString());
            outputPayload.put("pausedStage", "PENDING");
            outputPayload.put("message", "Generation paused before worker pickup.");
        }

        job.setOutputPayload(outputPayload);
        return generationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob pauseGenerationJob(UUID jobId, String stage, String message, Map<String, Object> outputPatch) {
        CreatorGenerationJob job = generationJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        if (isTerminalStatus(job.getStatus())) {
            return job;
        }
        Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
        if (outputPatch != null && !outputPatch.isEmpty()) {
            outputPayload.putAll(outputPatch);
        }
        clearExecutionClaim(outputPayload);
        outputPayload.put("pauseRequested", false);
        outputPayload.put("pausedAt", OffsetDateTime.now().toString());
        outputPayload.put("pausedStage", defaultString(stage, "UNKNOWN"));
        outputPayload.put("activeStage", defaultString(stage, "PAUSED"));
        outputPayload.put("message", defaultString(message, "Generation paused."));
        job.setStatus(GenerationJobStatus.PAUSED.name());
        job.setOutputPayload(outputPayload);
        job.setCompletedAt(null);
        return generationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob updatePausedGenerationJobPayload(UUID jobId, String tenantId, String userId, Map<String, Object> outputPatch) {
        CreatorGenerationJob job = generationJobRepository
                .findByIdAndTenantIdAndUserId(
                        jobId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        if (!GenerationJobStatus.PAUSED.name().equalsIgnoreCase(job.getStatus())) {
            throw new IllegalStateException("Generation job must be paused before timeline edits can be saved.");
        }
        Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
        if (outputPatch != null && !outputPatch.isEmpty()) {
            outputPayload.putAll(outputPatch);
        }
        outputPayload.put("humanEditedAt", OffsetDateTime.now().toString());
        outputPayload.put("message", stringValue(outputPayload.get("message"), "Timeline edits saved. Resume generation to continue."));
        job.setOutputPayload(outputPayload);
        return generationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob resumeGenerationJob(UUID jobId, String tenantId, String userId, Map<String, Object> outputPatch) {
        CreatorGenerationJob job = generationJobRepository
                .findByIdAndTenantIdAndUserId(
                        jobId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        if (isTerminalStatus(job.getStatus())) {
            throw new IllegalStateException("Completed or failed generation jobs cannot be resumed.");
        }
        Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
        if (outputPatch != null && !outputPatch.isEmpty()) {
            outputPayload.putAll(outputPatch);
        }
        clearExecutionClaim(outputPayload);
        outputPayload.remove("pauseRequested");
        outputPayload.put("resumedAt", OffsetDateTime.now().toString());
        outputPayload.put("message", "Generation resumed and queued.");
        job.setStatus(GenerationJobStatus.PENDING.name());
        job.setProgress(Math.max(0, Math.min(8, job.getProgress() == null ? 0 : job.getProgress())));
        job.setKafkaTopic(properties.getKafka().getGenerationJobsTopic());
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setErrorMessage(null);
        job.setOutputPayload(outputPayload);
        return generationJobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public Optional<CreatorGenerationJob> findActiveGenerationJobByIdempotencyKey(
            String tenantId,
            String userId,
            String jobType,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return generationJobRepository.findActiveByIdempotencyKey(
                defaultString(tenantId, "unknown"),
                defaultString(userId, "anonymous"),
                defaultString(jobType, "GENERATION"),
                idempotencyKey
        );
    }

    @Transactional(readOnly = true)
    public Optional<CreatorGenerationJob> findCompletedGenerationJobByIdempotencyKey(
            String tenantId,
            String userId,
            String jobType,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return generationJobRepository.findLatestCompletedByIdempotencyKey(
                defaultString(tenantId, "unknown"),
                defaultString(userId, "anonymous"),
                defaultString(jobType, "GENERATION"),
                idempotencyKey
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob completeGenerationJob(UUID jobId, Map<String, Object> outputPayload) {
        CreatorGenerationJob job = generationJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        if (isTerminalStatus(job.getStatus())) {
            return job;
        }
        job.setStatus(GenerationJobStatus.COMPLETED.name());
        job.setProgress(100);
        job.setOutputPayload(copyPayload(outputPayload));
        job.setCompletedAt(OffsetDateTime.now());
        return generationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob failGenerationJob(UUID jobId, String errorMessage) {
        return failGenerationJob(jobId, errorMessage, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob failGenerationJob(UUID jobId, String errorMessage, Map<String, Object> outputPatch) {
        CreatorGenerationJob job = generationJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        if (isTerminalStatus(job.getStatus())) {
            return job;
        }
        job.setStatus(GenerationJobStatus.FAILED.name());
        job.setProgress(100);
        job.setErrorMessage(errorMessage);
        Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
        if (errorMessage != null && !errorMessage.isBlank()) {
            outputPayload.put("message", errorMessage);
        }
        if (outputPatch != null && !outputPatch.isEmpty()) {
            outputPayload.putAll(outputPatch);
        }
        job.setOutputPayload(outputPayload);
        job.setCompletedAt(OffsetDateTime.now());
        return generationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob updateGenerationJobProgress(UUID jobId, int progress, String message) {
        return updateGenerationJobProgress(jobId, progress, message, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorGenerationJob updateGenerationJobProgress(UUID jobId, int progress, String message, Map<String, Object> outputPatch) {
        CreatorGenerationJob job = generationJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        if (isTerminalStatus(job.getStatus()) || GenerationJobStatus.PAUSED.name().equalsIgnoreCase(job.getStatus())) {
            return job;
        }
        job.setStatus(GenerationJobStatus.RUNNING.name());
        job.setProgress(Math.max(0, Math.min(99, progress)));
        Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
        if (message != null && !message.isBlank()) {
            outputPayload.put("message", message);
        }
        if (outputPatch != null && !outputPatch.isEmpty()) {
            outputPayload.putAll(outputPatch);
        }
        compactRunningShortsOutput(job, outputPayload);
        outputPayload.put("lastHeartbeatAt", OffsetDateTime.now().toString());
        job.setOutputPayload(outputPayload);
        if (job.getStartedAt() == null) {
            job.setStartedAt(OffsetDateTime.now());
        }
        return generationJobRepository.save(job);
    }

    private void compactRunningShortsOutput(CreatorGenerationJob job, Map<String, Object> outputPayload) {
        if (job == null || outputPayload == null) {
            return;
        }
        if (!SHORTS_COMPACT_RUNNING_JOB_TYPES.contains(defaultString(job.getJobType(), "").toUpperCase())) {
            return;
        }
        SHORTS_RUNNING_HEAVY_OUTPUT_KEYS.forEach(outputPayload::remove);
        outputPayload.put("runningOutputCompacted", true);
    }

    @Transactional(readOnly = true)
    public GenerationJobResponse getGenerationJob(UUID jobId, String tenantId, String userId) {
        CreatorGenerationJob job = generationJobRepository
                .findByIdAndTenantIdAndUserId(
                        jobId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new IllegalArgumentException("Generation job was not found: " + jobId));
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public List<GenerationJobResponse> listGenerationJobs(
            String tenantId,
            String userId,
            String jobType,
            UUID lockedIdeaId
    ) {
        String normalizedJobType = defaultString(jobType, "").trim();
        String normalizedLockedIdeaId = lockedIdeaId == null ? "" : lockedIdeaId.toString();
        return generationJobRepository
                .findTop20ByTenantIdAndUserIdOrderByCreatedAtDesc(
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .stream()
                .filter(job -> normalizedJobType.isBlank() || normalizedJobType.equalsIgnoreCase(job.getJobType()))
                .filter(job -> {
                    if (normalizedLockedIdeaId.isBlank()) {
                        return true;
                    }
                    String jobLockedIdeaId = stringValue(job.getInputPayload() == null ? null : job.getInputPayload().get("lockedIdeaId"), "");
                    return normalizedLockedIdeaId.equals(jobLockedIdeaId);
                })
                .map(this::toResponse)
                .toList();
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

    public void publishExistingGenerationJob(String kafkaTopic, UUID jobId, Map<String, Object> payload) {
        Map<String, Object> kafkaPayload = copyPayload(payload);
        kafkaPayload.put("jobId", jobId.toString());
        if (!kafkaPayload.containsKey("jobType")) {
            generationJobRepository.findById(jobId)
                    .map(CreatorGenerationJob::getJobType)
                    .ifPresent(jobType -> kafkaPayload.put("jobType", jobType));
        }
        kafkaTemplate.send(defaultString(kafkaTopic, properties.getKafka().getGenerationJobsTopic()), jobId.toString(), kafkaPayload);
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

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private void clearExecutionClaim(Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        payload.remove("executionClaimedAt");
        payload.remove("executionClaimedBy");
        payload.remove("lastHeartbeatAt");
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
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

    public GenerationJobResponse toResponse(CreatorGenerationJob job) {
        Map<String, Object> outputPayload = copyPayload(job.getOutputPayload());
        String message = stringValue(outputPayload.get("message"), defaultMessage(job));
        return new GenerationJobResponse(
                job.getId(),
                job.getJobType(),
                job.getStatus(),
                job.getProgress(),
                message,
                copyPayload(job.getInputPayload()),
                outputPayload,
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }

    private String defaultMessage(CreatorGenerationJob job) {
        String status = defaultString(job.getStatus(), GenerationJobStatus.PENDING.name());
        if (GenerationJobStatus.COMPLETED.name().equalsIgnoreCase(status)) {
            return "Generation completed";
        }
        if (GenerationJobStatus.FAILED.name().equalsIgnoreCase(status)) {
            return defaultString(job.getErrorMessage(), "Generation failed");
        }
        if (GenerationJobStatus.RUNNING.name().equalsIgnoreCase(status)) {
            return "Generation in progress";
        }
        if (GenerationJobStatus.PAUSED.name().equalsIgnoreCase(status)) {
            return "Generation paused";
        }
        return "Generation queued";
    }

    private boolean isTerminalStatus(String status) {
        return GenerationJobStatus.COMPLETED.name().equalsIgnoreCase(status)
                || GenerationJobStatus.FAILED.name().equalsIgnoreCase(status)
                || GenerationJobStatus.CANCELLED.name().equalsIgnoreCase(status);
    }
}
