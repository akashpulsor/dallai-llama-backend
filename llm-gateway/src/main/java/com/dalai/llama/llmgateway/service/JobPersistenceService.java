package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.JobStatus;
import com.dalai.llama.llmgateway.domain.entity.AuditLog;
import com.dalai.llama.llmgateway.domain.entity.LlmJob;
import com.dalai.llama.llmgateway.repository.AuditLogRepository;
import com.dalai.llama.llmgateway.repository.LlmJobRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every llm_job/audit_log state transition, each wrapped in its own real transaction.
 * Deliberately a separate bean from {@link LlmGatewayService}: {@code @Transactional} is applied
 * by a Spring proxy around the bean, so a method calling {@code this.otherMethod()} within the
 * SAME class silently skips the proxy and the annotation does nothing -- a classic pitfall.
 * Keeping persistence here means every call into it is a real external bean call, so the
 * transactional boundaries below are genuine, not decorative.
 */
@Service
public class JobPersistenceService {

    private final LlmJobRepository llmJobRepository;
    private final AuditLogRepository auditLogRepository;

    public JobPersistenceService(LlmJobRepository llmJobRepository, AuditLogRepository auditLogRepository) {
        this.llmJobRepository = llmJobRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /** @return the claimed row, or empty if a concurrent duplicate won the race on the unique
     * (tenant_id, idempotency_key) index -- the caller should re-read and treat it as a replay. */
    @Transactional
    public Optional<LlmJob> claimNewJob(String tenantId, String idempotencyKey, String modelId, UUID projectId) {
        LlmJob job = LlmJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(tenantId)
                .modelId(modelId)
                .projectId(projectId)
                .status(JobStatus.PROCESSING)
                .mode("sync")
                .idempotencyKey(idempotencyKey)
                .schemaVersion("1.0")
                .createdAt(OffsetDateTime.now())
                .processingStartedAt(OffsetDateTime.now())
                .attemptCount(1)
                .build();
        try {
            return Optional.of(llmJobRepository.saveAndFlush(job));
        } catch (DataIntegrityViolationException ex) {
            return Optional.empty();
        }
    }

    /** Recorded at dispatch time, before the provider call -- see {@link LlmJob#getRequestContent()}. */
    @Transactional
    public void recordRequest(UUID jobId, String requestContent) {
        LlmJob job = requireJob(jobId);
        job.setRequestContent(requestContent);
        llmJobRepository.save(job);
    }

    /** Resets an existing (already-terminal) row back to PROCESSING for a same-idempotency-key
     * retry, on the SAME job_id -- keeps the audit trail as one job, not one row per attempt. */
    @Transactional
    public LlmJob markProcessingForRetry(UUID jobId, int attemptNumber) {
        LlmJob job = requireJob(jobId);
        job.setStatus(JobStatus.PROCESSING);
        job.setProcessingStartedAt(OffsetDateTime.now());
        job.setAttemptCount(attemptNumber);
        job.setLastError(null);
        return llmJobRepository.save(job);
    }

    @Transactional
    public AuditLog finish(UUID jobId, JobStatus status, String error, int inputTokens, int outputTokens, BigDecimal cost, int latencyMs) {
        return finish(jobId, status, error, inputTokens, outputTokens, cost, latencyMs, null);
    }

    @Transactional
    public AuditLog finish(UUID jobId, JobStatus status, String error, int inputTokens, int outputTokens,
                            BigDecimal cost, int latencyMs, String resultContent) {
        LlmJob job = requireJob(jobId);
        job.setStatus(status);
        job.setCompletedAt(OffsetDateTime.now());
        job.setLastError(error);
        job.setResultContent(resultContent);
        // Persist cost on the job row itself (not only audit_log) -- this is what the per-project
        // cost rollup reads, and it lands here in real time at completion.
        job.setCost(cost);
        llmJobRepository.save(job);
        return writeAuditLog(job, inputTokens, outputTokens, cost, latencyMs, status, error);
    }

    /** Idempotent: no-ops (returns empty) if the row already reached a terminal state --
     * lets {@code cancel()} and a racing in-flight-call failure both call this safely, only
     * the first one to arrive actually writes. */
    @Transactional
    public Optional<AuditLog> finalizeIfStillProcessing(UUID jobId, JobStatus terminalStatus, String reason) {
        LlmJob job = requireJob(jobId);
        if (job.getStatus() != JobStatus.PROCESSING) {
            return Optional.empty();
        }
        job.setStatus(terminalStatus);
        job.setCompletedAt(OffsetDateTime.now());
        job.setLastError(reason);
        llmJobRepository.save(job);
        return Optional.of(writeAuditLog(job, 0, 0, BigDecimal.ZERO, 0, terminalStatus, reason));
    }

    private AuditLog writeAuditLog(LlmJob job, int inputTokens, int outputTokens, BigDecimal cost, int latencyMs, JobStatus status, String error) {
        AuditLog auditLog = AuditLog.builder()
                .auditId(UUID.randomUUID())
                .jobId(job.getJobId())
                .tenantId(job.getTenantId())
                .modelId(job.getModelId())
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cost(cost)
                .latencyMs(latencyMs)
                .status(status.name())
                .error(error)
                .createdAt(OffsetDateTime.now())
                .build();
        return auditLogRepository.save(auditLog);
    }

    private LlmJob requireJob(UUID jobId) {
        return llmJobRepository.findById(jobId)
                .orElseThrow(() -> GatewayException.notFound("Unknown job_id: " + jobId));
    }
}
