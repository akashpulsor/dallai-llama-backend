package com.dalai.llama.postprod.web;

import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId) {
}
