package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.PostProductionStatus;
import com.dalai.llama.postprod.domain.entity.PostProductionJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PostProductionJobRepository extends JpaRepository<PostProductionJob, UUID> {

    List<PostProductionJob> findByTenantIdAndProjectIdOrderByCreatedAtDesc(UUID tenantId, UUID projectId);

    List<PostProductionJob> findByStatusAndProcessingStartedAtBefore(PostProductionStatus status, OffsetDateTime cutoff);
}
