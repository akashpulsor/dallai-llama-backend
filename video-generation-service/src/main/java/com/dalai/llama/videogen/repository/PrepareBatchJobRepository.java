package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.PrepareBatchJobStatus;
import com.dalai.llama.videogen.domain.entity.PrepareBatchJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrepareBatchJobRepository extends JpaRepository<PrepareBatchJob, UUID> {

    Optional<PrepareBatchJob> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<PrepareBatchJob> findFirstByProjectIdAndStatusInOrderByCreatedAtDesc(
            UUID projectId, List<PrepareBatchJobStatus> statuses);
}
