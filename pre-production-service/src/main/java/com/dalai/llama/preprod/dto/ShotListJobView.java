package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ShotListJobStatus;
import com.dalai.llama.preprod.domain.entity.ShotListJob;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What the async shot-list generation endpoint returns on submit (as HTTP 202) and what the UI
 * polls repeatedly against the status endpoint until {@link #status} reaches SUCCEEDED or FAILED.
 * When SUCCEEDED, the UI re-fetches the shot list itself via the existing GET
 * /v1/projects/{projectId}/shots -- the shots are already persisted by the time this flips.
 */
public record ShotListJobView(
        UUID jobId,
        UUID projectId,
        ShotListJobStatus status,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
    public static ShotListJobView from(ShotListJob job) {
        return new ShotListJobView(
                job.getId(),
                job.getProjectId(),
                job.getStatus(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }
}
