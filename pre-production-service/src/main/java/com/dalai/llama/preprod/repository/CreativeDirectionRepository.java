package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.CreativeDirectionReviewStatus;
import com.dalai.llama.preprod.domain.entity.CreativeDirection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreativeDirectionRepository extends JpaRepository<CreativeDirection, UUID> {

    Optional<CreativeDirection> findByIdAndProjectIdAndTenantId(UUID id, UUID projectId, UUID tenantId);

    Optional<CreativeDirection> findByProjectIdAndReviewStatus(UUID projectId, CreativeDirectionReviewStatus reviewStatus);

    List<CreativeDirection> findByProjectIdAndReviewStatusIn(UUID projectId, List<CreativeDirectionReviewStatus> statuses);

    List<CreativeDirection> findByGenerationIdOrderByOptionNumberAscVersionDesc(UUID generationId);

    long countByProjectIdAndApprovedVia(UUID projectId, com.dalai.llama.preprod.domain.ReviewActor approvedVia);
}
