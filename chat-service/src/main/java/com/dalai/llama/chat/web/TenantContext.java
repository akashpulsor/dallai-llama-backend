package com.dalai.llama.chat.web;

import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId) {
}
