package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface VideoGenJobRepository extends JpaRepository<VideoGenJob, UUID> {

    List<VideoGenJob> findByTenantIdAndShotRefOrderByCreatedAtDesc(UUID tenantId, String shotRef);

    List<VideoGenJob> findByTenantIdAndProjectIdOrderByCreatedAtDesc(UUID tenantId, UUID projectId);

    /** Candidates for {@code StaleJobReconciliationTask} -- PROCESSING rows whose dispatch
     * started before the staleness cutoff, presumed crashed rather than genuinely still running. */
    List<VideoGenJob> findByStatusAndProcessingStartedAtBefore(JobStatus status, OffsetDateTime cutoff);
}
