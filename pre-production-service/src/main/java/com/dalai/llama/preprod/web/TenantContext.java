package com.dalai.llama.preprod.web;

import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId) {
}
