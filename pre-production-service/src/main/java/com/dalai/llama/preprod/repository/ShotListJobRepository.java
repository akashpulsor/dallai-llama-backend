package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotListJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShotListJobRepository extends JpaRepository<ShotListJob, UUID> {

    /** Fast lookup from the ChatJobCompletedConsumer -- the arriving event only carries the
     * idempotency key llm-gateway saw, not this service's local job id. */
    Optional<ShotListJob> findByLlmJobIdempotencyKey(String llmJobIdempotencyKey);

    /** Latest shot-list job for a project, used by the UI on mount to rehydrate a prior FAILED
     * (or still-PENDING) run after a page reload. Without this, a project that hit a terminal
     * failure showed the same empty-shot-list panel a fresh project shows, hiding both the reason
     * for the empty state and the fact that generation was already attempted. */
    Optional<ShotListJob> findTopByProjectIdAndTenantIdOrderByCreatedAtDesc(UUID projectId, UUID tenantId);
}
