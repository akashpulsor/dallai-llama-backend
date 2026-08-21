package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.entity.LlmJob;
import com.dalai.llama.llmgateway.repository.LlmJobRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Doc §5: dedup on (tenant_id, idempotency_key); a duplicate key returns the existing job's
 * current state instead of re-dispatching to the provider, even mid-flight. v1 requires a
 * client-supplied key (server-side hash-of-body fallback for an absent key is a one-line
 * follow-up, not required to prove the flow).
 */
@Service
public class IdempotencyService {

    private final LlmJobRepository llmJobRepository;

    public IdempotencyService(LlmJobRepository llmJobRepository) {
        this.llmJobRepository = llmJobRepository;
    }

    public Optional<LlmJob> findExisting(String tenantId, String idempotencyKey) {
        return llmJobRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    }
}
