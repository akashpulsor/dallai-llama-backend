package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.LlmJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LlmJobRepository extends JpaRepository<LlmJob, UUID> {

    Optional<LlmJob> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
}
