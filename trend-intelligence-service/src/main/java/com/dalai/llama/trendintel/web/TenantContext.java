package com.dalai.llama.trendintel.web;

import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId) {
}
