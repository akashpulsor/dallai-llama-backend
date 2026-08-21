package com.dalai.llama.creativeplanning.web;

import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId) {
}
