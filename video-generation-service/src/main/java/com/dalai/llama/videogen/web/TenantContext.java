package com.dalai.llama.videogen.web;

import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId) {
}
