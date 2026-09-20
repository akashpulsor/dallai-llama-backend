package com.dalai.llama.llmgateway.dto;

import com.dalai.llama.llmgateway.domain.JobStatus;
import com.dalai.llama.llmgateway.domain.entity.LlmJob;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin-facing summary of a single {@link LlmJob} for the ops dashboard. Deliberately narrower
 * than the entity: no {@code requestContent} or {@code resultContent} (bulky, and 99% of "why is
 * this stuck" answers come from the metadata), and {@code lastError} truncated to the first line
 * for a scannable list.
 *
 * <p>What "stuck" means is decided by the caller ({@code AdminLlmJobService.listStuck}); this DTO
 * only carries the state, not the classification.
 */
public record StuckLlmJobView(
        UUID jobId,
        String tenantId,
        UUID projectId,
        String modelId,
        String taskKey,
        JobStatus status,
        Integer attemptCount,
        OffsetDateTime createdAt,
        OffsetDateTime processingStartedAt,
        OffsetDateTime completedAt,
        String lastErrorSummary,
        BigDecimal cost
) {

    public static StuckLlmJobView from(LlmJob job) {
        return new StuckLlmJobView(
                job.getJobId(),
                job.getTenantId(),
                job.getProjectId(),
                job.getModelId(),
                job.getTaskKey(),
                job.getStatus(),
                job.getAttemptCount(),
                job.getCreatedAt(),
                job.getProcessingStartedAt(),
                job.getCompletedAt(),
                firstLine(job.getLastError()),
                job.getCost());
    }

    private static String firstLine(String value) {
        if (value == null) return null;
        int newline = value.indexOf('\n');
        String single = newline < 0 ? value : value.substring(0, newline);
        return single.length() > 240 ? single.substring(0, 240) + "…" : single;
    }
}
