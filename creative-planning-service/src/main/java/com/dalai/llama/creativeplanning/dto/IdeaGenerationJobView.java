package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.IdeaGenerationJobStatus;
import com.dalai.llama.creativeplanning.domain.entity.IdeaGenerationJob;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Wire shape the UI polls -- mirror of pre-production-service's ShotListJobView. Terminal
 * state (SUCCEEDED/FAILED) signals the frontend to stop polling and refetch the options list. */
public record IdeaGenerationJobView(
        UUID id,
        UUID projectRequirementId,
        IdeaGenerationJobStatus status,
        Integer requestedOptionCount,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt
) {
    public static IdeaGenerationJobView from(IdeaGenerationJob job) {
        return new IdeaGenerationJobView(
                job.getId(),
                job.getProjectRequirementId(),
                job.getStatus(),
                job.getRequestedOptionCount(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getCompletedAt());
    }
}
