package com.dalai.llama.critic.web;

import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId) {
}
