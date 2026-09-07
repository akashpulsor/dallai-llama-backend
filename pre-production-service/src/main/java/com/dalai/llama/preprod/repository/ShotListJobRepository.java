package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotListJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShotListJobRepository extends JpaRepository<ShotListJob, UUID> {

    /** Fast lookup from the ChatJobCompletedConsumer -- the arriving event only carries the
     * idempotency key llm-gateway saw, not this service's local job id. */
    Optional<ShotListJob> findByLlmJobIdempotencyKey(String llmJobIdempotencyKey);
}
