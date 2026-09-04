package com.dalai.llama.preprod.repository;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.preprod.domain.entity.ShotAssetBatchJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotAssetBatchJobRepository extends JpaRepository<ShotAssetBatchJob, UUID> {

    Optional<ShotAssetBatchJob> findTopByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** ShotAssetBatchWorker's queue read -- oldest non-terminal job across every project, so the
     * single worker drains a real FIFO queue rather than picking arbitrarily. */
    Optional<ShotAssetBatchJob> findFirstByStatusInOrderByCreatedAtAsc(List<JobLifecycleStatus> statuses);
}
