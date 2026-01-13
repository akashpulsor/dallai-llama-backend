package com.dalai.llama.tenant.domain.event;

import java.util.UUID;

public record TenantSuspendedEvent(UUID tenantId, String reason) {
}
