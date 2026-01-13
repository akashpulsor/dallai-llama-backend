package com.dalai.llama.tenant.domain.event;

import java.util.UUID;

public record TenantActivatedEvent(UUID tenantId) {
}
