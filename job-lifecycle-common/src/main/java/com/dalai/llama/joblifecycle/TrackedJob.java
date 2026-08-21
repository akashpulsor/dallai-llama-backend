package com.dalai.llama.joblifecycle;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Contract a JPA job entity implements to plug into {@link AbstractJobLifecycleService} and
 * {@link AbstractStaleJobReconciliationTask}. Entities keep their own identity column and
 * whatever domain fields they need -- this only names the columns the shared lifecycle
 * machinery reads and writes.
 */
public interface TrackedJob {

    UUID getJobId();

    JobLifecycleStatus getStatus();

    void setStatus(JobLifecycleStatus status);

    OffsetDateTime getProcessingStartedAt();

    void setProcessingStartedAt(OffsetDateTime processingStartedAt);

    String getLastError();

    void setLastError(String lastError);

    OffsetDateTime getCompletedAt();

    void setCompletedAt(OffsetDateTime completedAt);
}
