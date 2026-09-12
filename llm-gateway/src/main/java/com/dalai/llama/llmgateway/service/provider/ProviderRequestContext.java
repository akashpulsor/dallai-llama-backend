package com.dalai.llama.llmgateway.service.provider;

import java.util.UUID;

/**
 * Request identity retained inside llm-gateway while an adapter executes a provider call. It is
 * deliberately separate from public {@code params}: provider adapters can use it for trusted,
 * tenant-scoped platform lookups without teaching callers about internal service credentials.
 */
public record ProviderRequestContext(String tenantId, UUID projectId) {
}
