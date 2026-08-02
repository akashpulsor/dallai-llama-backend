package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "creator.ai", name = "shorts-generation-stale-job-recovery-enabled", havingValue = "true", matchIfMissing = true)
public class CreatorShortGenerationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CreatorShortGenerationRecoveryScheduler.class);
    private static final String JOB_TYPE_GENERATE = "SHORTS_GENERATE";
    private static final String JOB_TYPE_VISUAL_ANALYSIS = "SHORTS_VISUAL_ANALYSIS";
    private static final String JOB_TYPE_RERENDER = "SHORTS_CANDIDATE_RERENDER";

    private final GenerationJobService generationJobService;

    @Value("${creator.ai.shorts-generation-stale-job-timeout-ms:7200000}")
    private long staleJobTimeoutMs;

    @Value("${creator.ai.shorts-generation-stale-job-recovery-batch-size:10}")
    private int recoveryBatchSize;

    public CreatorShortGenerationRecoveryScheduler(GenerationJobService generationJobService) {
        this.generationJobService = generationJobService;
    }

    @Scheduled(
            fixedDelayString = "${creator.ai.shorts-generation-stale-job-recovery-scan-ms:300000}",
            initialDelayString = "${creator.ai.shorts-generation-stale-job-recovery-initial-delay-ms:300000}"
    )
    public void recoverStaleShortGenerationJobs() {
        long timeoutMs = Math.max(Duration.ofMinutes(5).toMillis(), staleJobTimeoutMs);
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(Duration.ofMillis(timeoutMs));
        List<CreatorGenerationJob> staleJobs = generationJobService.findStaleClaimedShortGenerationJobs(staleBefore, recoveryBatchSize);
        if (staleJobs.isEmpty()) {
            return;
        }
        log.warn("Generate Shorts stale job recovery found count={} staleBefore={}", staleJobs.size(), staleBefore);
        for (CreatorGenerationJob job : staleJobs) {
            recover(job, staleBefore);
        }
    }

    private void recover(CreatorGenerationJob job, OffsetDateTime staleBefore) {
        if (job == null || job.getId() == null) {
            return;
        }
        Map<String, Object> payload = recoveryPayload(job);
        String jobType = defaultString(payload.get("jobType"), job.getJobType());
        String shortVideoId = defaultString(payload.get("shortVideoId"), "");
        String sourceAssetId = defaultString(payload.get("sourceAssetId"), "");
        if (shortVideoId.isBlank() || ((JOB_TYPE_GENERATE.equals(jobType) || JOB_TYPE_VISUAL_ANALYSIS.equals(jobType)) && sourceAssetId.isBlank())) {
            log.error(
                    "Generate Shorts stale job recovery skipped malformed jobId={} jobType={} shortVideoId={} sourceAssetId={}",
                    job.getId(),
                    jobType,
                    shortVideoId,
                    sourceAssetId
            );
            return;
        }

        String reason = "No Generate Shorts heartbeat after " + Math.max(Duration.ofMinutes(5).toMillis(), staleJobTimeoutMs) + "ms";
        if (!generationJobService.clearStaleShortGenerationClaimForRetry(job.getId(), staleBefore, reason)) {
            log.info("Generate Shorts stale job recovery skipped because job changed jobId={} jobType={}", job.getId(), jobType);
            return;
        }
        generationJobService.publishExistingGenerationJob(job.getKafkaTopic(), job.getId(), payload);
        log.warn(
                "Generate Shorts stale job recovered and requeued jobId={} jobType={} shortVideoId={} sourceAssetId={} candidateId={}",
                job.getId(),
                jobType,
                shortVideoId,
                sourceAssetId,
                defaultString(payload.get("candidateId"), "")
        );
    }

    private Map<String, Object> recoveryPayload(CreatorGenerationJob job) {
        Map<String, Object> payload = copyMap(job.getInputPayload());
        String jobType = defaultString(payload.get("jobType"), job.getJobType());
        payload.put("jobType", jobType);
        payload.put("tenantId", job.getTenantId());
        payload.put("userId", job.getUserId());
        payload.put("projectId", job.getProjectId() == null ? null : job.getProjectId().toString());
        payload.put("recoveredAt", OffsetDateTime.now().toString());

        if (JOB_TYPE_GENERATE.equals(jobType) && objectMap(payload.get("request")).isEmpty()) {
            payload.put("request", requestFromSettings(job));
        }

        Map<String, Object> queue = objectMap(payload.get("shortsQueue"));
        queue.put("jobType", jobType);
        putIfPresent(queue, "shortVideoId", payload.get("shortVideoId"));
        putIfPresent(queue, "sourceAssetId", payload.get("sourceAssetId"));
        putIfPresent(queue, "candidateId", payload.get("candidateId"));
        queue.put("recoveredAt", OffsetDateTime.now().toString());
        payload.put("shortsQueue", queue);
        return payload;
    }

    private Map<String, Object> requestFromSettings(CreatorGenerationJob job) {
        Map<String, Object> input = copyMap(job.getInputPayload());
        Map<String, Object> settings = objectMap(input.get("settings"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("projectId", job.getProjectId() == null ? null : job.getProjectId().toString());
        putIfPresent(request, "platform", settings.get("platform"));
        putIfPresent(request, "targetDurationSeconds", settings.get("targetDurationSeconds"));
        putIfPresent(request, "requestedShorts", settings.get("requestedShorts"));
        putIfPresent(request, "reviewMode", settings.get("reviewMode"));
        return request;
    }

    private Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
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

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private String defaultString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
